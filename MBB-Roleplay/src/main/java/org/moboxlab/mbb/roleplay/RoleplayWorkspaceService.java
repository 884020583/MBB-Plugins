package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * 角色私有工作区。
 *
 * 所有路径都必须落在插件数据目录下的 data/ 中，拒绝绝对路径、父目录逃逸和符号链接。
 */
public class RoleplayWorkspaceService {
    private static final String TRASH = ".trash";
    private static final String HISTORY = ".history";
    private static final Set<String> IMAGE_EXTENSIONS = new HashSet<>(Arrays.asList(
            "jpg","jpeg","png","gif","webp"));
    private static final Set<String> AUDIO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp3","wav","ogg","m4a","flac","silk","amr"));
    private static final Set<String> VIDEO_EXTENSIONS = new HashSet<>(Arrays.asList(
            "mp4","webm","mov"));
    private final Plugin plugin;
    private volatile RoleplayConfig config;

    public RoleplayWorkspaceService(Plugin plugin,RoleplayConfig config) {
        this.plugin = plugin;
        this.config = config;
        ensureRoot();
    }

    public void reload(RoleplayConfig config) {
        this.config = config;
        ensureRoot();
    }

    public JSONObject list(String path) throws Exception {
        File target = resolve(path,true);
        rejectInternalNamespace(target);
        if (!target.exists()) throw new IllegalArgumentException("路径不存在");
        if (!target.isDirectory()) throw new IllegalArgumentException("目标不是文件夹");
        File[] files = target.listFiles();
        JSONArray items = new JSONArray();
        int limit = Math.max(1,Math.min(1000,config.workspaceMaxFilesPerList));
        if (files != null) {
            List<File> sorted = new ArrayList<>();
            for (File file : files) sorted.add(file);
            sorted.sort((left,right) -> left.getName().compareToIgnoreCase(right.getName()));
            for (File file : sorted) {
                if (items.size() >= limit) break;
                if (isInternalNamespace(file)) continue;
                JSONObject item = new JSONObject(true);
                item.put("name",file.getName());
                item.put("path",relative(file));
                item.put("directory",file.isDirectory() && !Files.isSymbolicLink(file.toPath()));
                item.put("size",file.isFile() ? file.length() : 0L);
                item.put("updateTime",file.lastModified());
                items.add(item);
            }
        }
        JSONObject result = new JSONObject(true);
        result.put("path",relative(target));
        result.put("items",items);
        result.put("truncated",files != null && files.length > items.size());
        return result;
    }

    public JSONObject read(String path) throws Exception {
        File target = resolve(path,false);
        rejectInternalNamespace(target);
        if (!target.isFile()) throw new IllegalArgumentException("目标不存在或不是文件");
        rejectSymlink(target);
        long size = target.length();
        if (size > config.workspaceMaxReadChars * 4L) {
            throw new IllegalArgumentException("文件过大，最多读取约 "+config.workspaceMaxReadChars+" 字符");
        }
        String text = new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
        boolean truncated = text.length() > config.workspaceMaxReadChars;
        if (truncated) text = text.substring(0,config.workspaceMaxReadChars);
        JSONObject result = new JSONObject(true);
        result.put("path",relative(target));
        result.put("content",text);
        result.put("size",size);
        result.put("truncated",truncated);
        return result;
    }

    public String readBase64(String path,int maxBytes) throws Exception {
        File target = resolve(path,false);
        rejectInternalNamespace(target);
        if (!target.isFile()) throw new IllegalArgumentException("目标不存在或不是文件");
        rejectSymlink(target);
        if (target.length() > maxBytes) {
            throw new IllegalArgumentException("文件过大，最多 "+maxBytes+" 字节");
        }
        return Base64.getEncoder().encodeToString(Files.readAllBytes(target.toPath()));
    }

    public JSONObject importImage(String path,byte[] bytes,String mime,String source,
                                  boolean overwrite) throws Exception {
        if (!config.workspaceEnable) throw new IllegalArgumentException("角色工作区未启用");
        if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("图片内容为空");
        int maxBytes = Math.max(1024,config.workspaceMaxMediaBytes);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("图片过大，最多 "+maxBytes+" 字节");
        }
        String format = imageFormat(bytes,mime);
        if (format.isEmpty()) throw new IllegalArgumentException("只支持 JPEG、PNG、GIF、WebP 图片");
        String relativePath = safePath(path);
        String fileName = generatedMediaName("image",format);
        if (relativePath.isEmpty()) {
            relativePath = "media/images/"+fileName;
        } else if (relativePath.endsWith("/")) {
            relativePath += fileName;
        } else if (!IMAGE_EXTENSIONS.contains(extension(relativePath))) {
            relativePath += "."+format;
        }
        File target = resolve(relativePath,false);
        rejectInternalNamespace(target);
        rejectSymlink(target);
        if (target.exists() && !overwrite) throw new FileAlreadyExistsException(relativePath);
        writeBytes(target,bytes,overwrite);
        JSONObject result = new JSONObject(true);
        result.put("path",relative(target));
        result.put("mediaType","image");
        result.put("format",format);
        result.put("mime",mime == null ? "image/"+format : mime);
        result.put("size",bytes.length);
        result.put("sha256",sha256(bytes));
        result.put("source",serviceSafe(source));
        result.put("updateTime",target.lastModified());
        return result;
    }

    public JSONObject mediaList(String path,String kind,int maxResults) throws Exception {
        File start = resolve(path,true);
        rejectInternalNamespace(start);
        if (!start.exists()) throw new IllegalArgumentException("路径不存在");
        int limit = Math.max(1,Math.min(200,maxResults <= 0 ? 50 : maxResults));
        String filter = serviceSafe(kind).trim().toLowerCase(Locale.ROOT);
        if (!"image".equals(filter) && !"audio".equals(filter)
                && !"video".equals(filter)) filter = "all";
        List<File> files = new ArrayList<>();
        collectFiles(start,files,5000);
        List<JSONObject> items = new ArrayList<>();
        for (File file : files) {
            if (isInternalNamespace(file)) continue;
            String extension = extension(file.getName());
            String mediaType = mediaType(extension);
            if (mediaType.isEmpty()) continue;
            if (!"all".equals(filter) && !filter.equals(mediaType)) continue;
            JSONObject item = new JSONObject(true);
            item.put("name",file.getName());
            item.put("path",relative(file));
            item.put("mediaType",mediaType);
            item.put("extension",extension);
            item.put("size",file.length());
            item.put("updateTime",file.lastModified());
            items.add(item);
        }
        items.sort((left,right) -> Long.compare(
                right.getLongValue("updateTime"),left.getLongValue("updateTime")));
        JSONArray resultItems = new JSONArray();
        for (int i = 0; i < items.size() && i < limit; i++) resultItems.add(items.get(i));
        JSONObject result = new JSONObject(true);
        result.put("path",relative(start));
        result.put("kind",filter);
        result.put("items",resultItems);
        result.put("truncated",items.size() > resultItems.size());
        return result;
    }

    public JSONObject appendNote(String kind,String title,String content,JSONArray tags)
            throws Exception {
        String noteKind = normalizeNoteKind(kind);
        String text = serviceSafe(content).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("笔记内容不能为空");
        if (text.length() > config.workspaceMaxWriteChars) {
            throw new IllegalArgumentException("笔记内容过长，最多 "
                    +config.workspaceMaxWriteChars+" 字符");
        }
        String name = safeName(title);
        String date = new java.text.SimpleDateFormat("yyyy-MM-dd",Locale.CHINA)
                .format(new java.util.Date());
        String month = date.substring(0,7);
        String path;
        if ("journal".equals(noteKind)) {
            path = "notes/journal/"+date+".md";
            if ("未命名".equals(name)) name = date;
        } else if ("person".equals(noteKind)) {
            if ("未命名".equals(name)) throw new IllegalArgumentException("人物笔记需要 title");
            path = "notes/people/"+name+".md";
        } else if ("reference".equals(noteKind)) {
            if ("未命名".equals(name)) throw new IllegalArgumentException("资料笔记需要 title");
            path = "notes/references/"+name+".md";
        } else if ("idea".equals(noteKind)) {
            path = "notes/ideas/"+("未命名".equals(name) ? date : name)+".md";
        } else {
            path = "notes/general/"+("未命名".equals(name) ? date : name)+".md";
        }
        String time = new java.text.SimpleDateFormat("HH:mm",Locale.CHINA)
                .format(new java.util.Date());
        StringBuilder block = new StringBuilder();
        block.append("\n## ").append(time).append(" ").append(name).append("\n");
        String tagText = tagText(tags);
        if (!tagText.isEmpty()) block.append("- 标签：").append(tagText).append("\n");
        block.append(text).append("\n");
        JSONObject written = write(path,block.toString(),true);
        written.put("kind",noteKind);
        written.put("title",name);
        return written;
    }

    public JSONObject saveDraft(String kind,String title,String content,JSONArray tags)
            throws Exception {
        String draftKind = normalizeDraftKind(kind);
        String text = serviceSafe(content).trim();
        if (text.isEmpty()) throw new IllegalArgumentException("草稿内容不能为空");
        if (text.length() > config.workspaceMaxWriteChars) {
            throw new IllegalArgumentException("草稿内容过长，最多 "
                    +config.workspaceMaxWriteChars+" 字符");
        }
        String name = safeName(title);
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.CHINA)
                .format(new java.util.Date());
        String suffix = UUID.randomUUID().toString().replace("-","").substring(0,6);
        String path = "drafts/"+draftKind+"/"+stamp+"-"+suffix+"-"+name+".md";
        String tagText = tagText(tags);
        StringBuilder body = new StringBuilder();
        body.append("# ").append(name).append("\n\n")
                .append("- 类型：").append(draftKind).append("\n")
                .append("- 创建时间：").append(nowText()).append("\n");
        if (!tagText.isEmpty()) body.append("- 标签：").append(tagText).append("\n");
        body.append("\n").append(text).append("\n");
        JSONObject written = write(path,body.toString(),false);
        written.put("kind",draftKind);
        written.put("title",name);
        return written;
    }

    public JSONObject write(String path,String content,boolean append) throws Exception {
        if (!config.workspaceEnable) throw new IllegalArgumentException("角色工作区未启用");
        File target = resolve(path,false);
        rejectInternalNamespace(target);
        if (target.isDirectory()) throw new IllegalArgumentException("目标是一个文件夹");
        rejectSymlink(target);
        String text = content == null ? "" : content;
        if (text.length() > config.workspaceMaxWriteChars) {
            throw new IllegalArgumentException("单次写入过长，最多 "+config.workspaceMaxWriteChars+" 字符");
        }
        File parent = target.getParentFile();
        if (parent == null) throw new IllegalArgumentException("目标路径无效");
        if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建父目录");
        verifyInsideRoot(parent.getCanonicalFile());
        if (append && target.exists()) {
            String old = new String(Files.readAllBytes(target.toPath()),StandardCharsets.UTF_8);
            text = old + text;
            if (text.length() > config.workspaceMaxWriteChars) {
                throw new IllegalArgumentException("追加后文件过长，最多 "+config.workspaceMaxWriteChars+" 字符");
            }
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long oldSize = target.exists() ? target.length() : 0L;
        long total = directorySize(root());
        if (total - oldSize + bytes.length > config.workspaceMaxTotalBytes) {
            throw new IllegalArgumentException("工作区总大小超过上限 "
                    +config.workspaceMaxTotalBytes+" 字节");
        }
        if (target.exists() && config.workspaceHistoryEnable) backupHistory(target);
        Path temp = parent.toPath().resolve("."+target.getName()+".tmp-"
                +UUID.randomUUID().toString().replace("-",""));
        Files.write(temp,bytes);
        try {
            Files.move(temp,target.toPath(),StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
        JSONObject result = new JSONObject(true);
        result.put("path",relative(target));
        result.put("size",target.length());
        result.put("append",append);
        return result;
    }

    public JSONObject delete(String path,boolean recursive) throws Exception {
        return delete(path,recursive,false);
    }

    public JSONObject delete(String path,boolean recursive,boolean permanent) throws Exception {
        if (!config.workspaceEnable) throw new IllegalArgumentException("角色工作区未启用");
        File target = resolve(path,false);
        rejectInternalNamespace(target);
        if (!target.exists()) throw new IllegalArgumentException("路径不存在");
        rejectSymlink(target);
        if (target.isDirectory()) {
            File[] files = target.listFiles();
            if (files != null && files.length > 0 && !recursive) {
                throw new IllegalArgumentException("文件夹非空；确认删除请传 recursive=true");
            }
        }
        JSONObject result = new JSONObject(true);
        if (permanent) {
            if (target.isDirectory()) deleteRecursive(target);
            else if (!target.delete()) throw new IllegalStateException("删除文件失败");
            result.put("permanent",true);
            result.put("path",path);
            return result;
        }
        File trash = new File(root(),TRASH);
        if (!trash.exists() && !trash.mkdirs()) throw new IllegalStateException("无法创建工作区回收站");
        File dest = new File(trash,System.currentTimeMillis()+"-"+target.getName());
        move(target,dest,false);
        dest.setLastModified(System.currentTimeMillis());
        result.put("permanent",false);
        result.put("path",path);
        result.put("trashPath",relative(dest));
        result.put("restoreHint","可调用 workspace_restore 恢复");
        cleanupTrash();
        return result;
    }

    public JSONObject rename(String source,String target,boolean overwrite) throws Exception {
        File from = resolve(source,false);
        File to = resolve(target,false);
        rejectInternalNamespace(from);
        rejectInternalNamespace(to);
        if (!from.exists()) throw new IllegalArgumentException("源路径不存在");
        if (from.equals(to)) return pathResult(to,"rename");
        rejectNestedTarget(from,to);
        rejectSymlink(from);
        rejectSymlink(to);
        move(from,to,overwrite);
        return pathResult(to,"rename");
    }

    public JSONObject copy(String source,String target,boolean overwrite) throws Exception {
        File from = resolve(source,false);
        File to = resolve(target,false);
        rejectInternalNamespace(from);
        rejectInternalNamespace(to);
        if (!from.exists()) throw new IllegalArgumentException("源路径不存在");
        rejectNestedTarget(from,to);
        rejectSymlink(from);
        rejectSymlink(to);
        if (to.exists() && !overwrite) throw new FileAlreadyExistsException(target);
        File parent = to.getParentFile();
        if (parent == null) throw new IllegalArgumentException("目标路径无效");
        if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建父目录");
        verifyInsideRoot(parent.getCanonicalFile());
        long total = directorySize(root());
        long sourceSize = directorySize(from);
        long oldSize = to.exists() ? directorySize(to) : 0L;
        if (total - oldSize + sourceSize > config.workspaceMaxTotalBytes) {
            throw new IllegalArgumentException("复制后工作区总大小超过上限 "
                    +config.workspaceMaxTotalBytes+" 字节");
        }
        if (to.exists()) {
            if (to.isDirectory()) deleteRecursive(to);
            else Files.deleteIfExists(to.toPath());
        }
        copyRecursive(from,to);
        return pathResult(to,"copy");
    }

    public JSONObject restore(String trashPath,String target,boolean overwrite) throws Exception {
        File from = resolve(trashPath,false);
        File trash = new File(root(),TRASH).getCanonicalFile();
        if (!from.getCanonicalPath().startsWith(trash.getPath()+File.separator)) {
            throw new IllegalArgumentException("只能从工作区回收站恢复");
        }
        if (!from.exists()) throw new IllegalArgumentException("回收站路径不存在");
        String name = from.getName();
        int split = name.indexOf('-');
        if (split >= 0 && split + 1 < name.length()) name = name.substring(split + 1);
        File to = resolve(target == null || target.trim().isEmpty() ? name : target,false);
        rejectSymlink(from);
        rejectSymlink(to);
        move(from,to,overwrite);
        return pathResult(to,"restore");
    }

    public JSONObject search(String path,String query,int maxResults) throws Exception {
        File root = resolve(path,true);
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) throw new IllegalArgumentException("搜索内容不能为空");
        int limit = Math.max(1,Math.min(100,maxResults));
        List<File> files = new ArrayList<>();
        collectFiles(root,files,2000);
        JSONArray matches = new JSONArray();
        for (File file : files) {
            if (matches.size() >= limit) break;
            if (!file.isFile() || file.length() > config.workspaceMaxReadChars * 4L) continue;
            String text = new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8);
            String[] lines = text.split("\\n",-1);
            for (int i = 0; i < lines.length; i++) {
                if (!lines[i].toLowerCase(Locale.ROOT).contains(needle)) continue;
                JSONObject item = new JSONObject(true);
                item.put("path",relative(file));
                item.put("line",i + 1);
                item.put("text",lines[i].length() > 300 ? lines[i].substring(0,300) : lines[i]);
                matches.add(item);
                if (matches.size() >= limit) break;
            }
        }
        JSONObject result = new JSONObject(true);
        result.put("matches",matches);
        return result;
    }

    private void writeBytes(File target,byte[] bytes,boolean overwrite) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) throw new IllegalArgumentException("目标路径无效");
        if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建父目录");
        verifyInsideRoot(parent.getCanonicalFile());
        long oldSize = target.exists() ? target.length() : 0L;
        if (target.exists() && !overwrite) throw new FileAlreadyExistsException(target.getPath());
        long total = directorySize(root());
        if (total - oldSize + bytes.length > config.workspaceMaxTotalBytes) {
            throw new IllegalArgumentException("工作区总大小超过上限 "
                    +config.workspaceMaxTotalBytes+" 字节");
        }
        if (target.exists() && config.workspaceHistoryEnable) backupHistory(target);
        Path temp = parent.toPath().resolve("."+target.getName()+".tmp-"
                +UUID.randomUUID().toString().replace("-",""));
        try {
            Files.write(temp,bytes);
            try {
                Files.move(temp,target.toPath(),StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp,target.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private String generatedMediaName(String prefix,String extension) {
        String time = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",Locale.CHINA)
                .format(new java.util.Date());
        String suffix = UUID.randomUUID().toString().replace("-","").substring(0,6);
        return prefix+"-"+time+"-"+suffix+"."+extension;
    }

    private String imageFormat(byte[] bytes,String mime) {
        if (bytes == null || bytes.length < 4) return "";
        if ((bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                && (bytes[2] & 0xff) == 0xff) return "jpg";
        if ((bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50
                && bytes[2] == 0x4e && bytes[3] == 0x47) return "png";
        if (bytes[0] == 0x47 && bytes[1] == 0x49
                && bytes[2] == 0x46 && bytes[3] == 0x38) return "gif";
        if (bytes.length >= 12 && bytes[0] == 0x52 && bytes[1] == 0x49
                && bytes[2] == 0x46 && bytes[3] == 0x46
                && bytes[8] == 0x57 && bytes[9] == 0x45
                && bytes[10] == 0x42 && bytes[11] == 0x50) return "webp";
        return "";
    }

    private String mediaType(String extension) {
        String value = serviceSafe(extension).toLowerCase(Locale.ROOT);
        if (IMAGE_EXTENSIONS.contains(value)) return "image";
        if (AUDIO_EXTENSIONS.contains(value)) return "audio";
        if (VIDEO_EXTENSIONS.contains(value)) return "video";
        return "";
    }

    private String extension(String name) {
        String value = serviceSafe(name);
        int dot = value.lastIndexOf('.');
        if (dot < 0 || dot + 1 >= value.length()) return "";
        return value.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] value = digest.digest(bytes);
        StringBuilder builder = new StringBuilder();
        for (byte item : value) builder.append(String.format("%02x",item & 0xff));
        return builder.toString();
    }

    private String normalizeNoteKind(String kind) {
        String value = serviceSafe(kind).trim().toLowerCase(Locale.ROOT);
        if ("person".equals(value) || "reference".equals(value)
                || "idea".equals(value) || "general".equals(value)) return value;
        return "journal";
    }

    private String normalizeDraftKind(String kind) {
        String value = serviceSafe(kind).trim().toLowerCase(Locale.ROOT);
        if ("reply".equals(value) || "qzone".equals(value)
                || "draw".equals(value) || "story".equals(value)) return value;
        return "other";
    }

    private String safeName(String value) {
        String name = serviceSafe(value).trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]","_")
                .replaceAll("\\s+","_").replaceAll("^\\.+|\\.+$","");
        if (name.isEmpty()) return "未命名";
        return name.length() > 60 ? name.substring(0,60) : name;
    }

    private String safePath(String value) {
        return serviceSafe(value).trim().replace("\\","/");
    }

    private String tagText(JSONArray tags) {
        if (tags == null || tags.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        for (Object object : tags) {
            String value = serviceSafe(String.valueOf(object)).trim();
            if (value.isEmpty()) continue;
            if (builder.length() > 0) builder.append(", ");
            builder.append(value.length() > 24 ? value.substring(0,24) : value);
        }
        return builder.toString();
    }

    private String nowText() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.CHINA)
                .format(new java.util.Date());
    }

    private String serviceSafe(String value) {
        return value == null ? "" : value;
    }

    public String rootPath() {
        return root().getAbsolutePath();
    }

    public JSONObject selfTest() {
        JSONObject result = new JSONObject(true);
        boolean relativeRejected = false;
        try {
            resolve("../outside-workspace",false);
        } catch (Exception e) {
            relativeRejected = true;
        }
        boolean absoluteRejected = false;
        try {
            resolve(new File(plugin.getDataFolder(),"outside-workspace").getAbsolutePath(),false);
        } catch (Exception e) {
            absoluteRejected = true;
        }
        boolean rootProtected = false;
        try {
            resolve("",false);
        } catch (Exception e) {
            rootProtected = true;
        }
        result.put("relativeEscapeRejected",relativeRejected);
        result.put("absolutePathRejected",absoluteRejected);
        result.put("rootDeleteProtected",rootProtected);
        result.put("status",relativeRejected && absoluteRejected && rootProtected);
        return result;
    }

    private File root() {
        File root = new File(plugin.getDataFolder(),"data");
        if (!root.exists() && !root.mkdirs()) {
            throw new IllegalStateException("无法创建角色工作区："+root.getAbsolutePath());
        }
        return root;
    }

    private void ensureRoot() {
        root();
        cleanupTrash();
    }

    private File resolve(String path,boolean allowRoot) throws Exception {
        if (!config.workspaceEnable) {
            throw new IllegalArgumentException("角色工作区未启用");
        }
        String value = path == null || path.trim().isEmpty() ? "." : path.trim();
        File raw = new File(value);
        if (raw.isAbsolute()) throw new IllegalArgumentException("只允许使用相对路径");
        File root = root().getCanonicalFile();
        File target = new File(root,value).getCanonicalFile();
        verifyInsideRoot(target);
        if (!allowRoot && target.equals(root)) throw new IllegalArgumentException("不能直接操作工作区根目录");
        return target;
    }

    private void verifyInsideRoot(File target) throws Exception {
        File root = root().getCanonicalFile();
        String rootPath = root.getPath();
        String targetPath = target.getCanonicalPath();
        if (!targetPath.equals(rootPath)
                && !targetPath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("路径越出角色工作区");
        }
    }

    private void rejectSymlink(File file) throws Exception {
        if (file != null && Files.isSymbolicLink(file.toPath())) {
            throw new IllegalArgumentException("不允许通过符号链接操作外部文件");
        }
    }

    private void collectFiles(File file,List<File> result,int max) {
        if (file == null || result.size() >= max) return;
        if (file.isFile()) {
            if (!Files.isSymbolicLink(file.toPath())) result.add(file);
            return;
        }
        File[] files = file.listFiles();
        if (files == null) return;
        for (File child : files) {
            if (Files.isSymbolicLink(child.toPath())) continue;
            collectFiles(child,result,max);
            if (result.size() >= max) return;
        }
    }

    private void deleteRecursive(File file) throws Exception {
        if (Files.isSymbolicLink(file.toPath())) {
            Files.delete(file.toPath());
            return;
        }
        if (file.isDirectory()) {
            File[] files = file.listFiles();
            if (files != null) {
                for (File child : files) deleteRecursive(child);
            }
        }
        Files.deleteIfExists(file.toPath());
    }

    private void copyRecursive(File source,File target) throws Exception {
        if (Files.isSymbolicLink(source.toPath())) {
            throw new IllegalArgumentException("不允许复制符号链接");
        }
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IllegalStateException("无法创建目录："+target.getName());
            }
            File[] files = source.listFiles();
            if (files != null) {
                for (File child : files) {
                    copyRecursive(child,new File(target,child.getName()));
                }
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("无法创建父目录");
        }
        Files.copy(source.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.COPY_ATTRIBUTES);
    }

    private void move(File source,File target,boolean overwrite) throws Exception {
        File parent = target.getParentFile();
        if (parent == null) throw new IllegalArgumentException("目标路径无效");
        if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("无法创建父目录");
        verifyInsideRoot(parent.getCanonicalFile());
        if (target.exists()) {
            if (!overwrite) throw new FileAlreadyExistsException(target.getPath());
            if (target.isDirectory()) deleteRecursive(target);
            else Files.deleteIfExists(target.toPath());
        }
        try {
            Files.move(source.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(),target.toPath());
        }
    }

    private void backupHistory(File target) throws Exception {
        if (target == null || !target.isFile()) return;
        File historyRoot = new File(root(),HISTORY);
        String relative = relative(target).replace("/",File.separator);
        File directory = new File(historyRoot,relative);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("无法创建工作区历史目录");
        }
        String stamp = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss-SSS",
                Locale.CHINA).format(new java.util.Date());
        Files.copy(target.toPath(),new File(directory,stamp+".bak").toPath(),
                StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.COPY_ATTRIBUTES);
        pruneHistory(directory);
        if (directorySize(root()) > config.workspaceMaxTotalBytes) {
            File[] files = directory.listFiles();
            if (files != null && files.length > 0) Files.deleteIfExists(files[0].toPath());
        }
    }

    private void pruneHistory(File directory) {
        int keep = Math.max(0,config.workspaceHistoryMaxVersions);
        File[] files = directory.listFiles();
        if (files == null || files.length <= keep) return;
        List<File> sorted = new ArrayList<>();
        for (File file : files) sorted.add(file);
        sorted.sort(new Comparator<File>() {
            @Override
            public int compare(File left,File right) {
                return Long.compare(left.lastModified(),right.lastModified());
            }
        });
        int remove = sorted.size() - keep;
        for (int i = 0; i < remove; i++) {
            try {
                Files.deleteIfExists(sorted.get(i).toPath());
            } catch (Exception ignored) {
            }
        }
        if (keep == 0) deleteRecursiveQuietly(directory);
    }

    private void cleanupTrash() {
        File trash = new File(root(),TRASH);
        if (!trash.exists() || !trash.isDirectory()) return;
        long cutoff = System.currentTimeMillis() - config.workspaceTrashKeepDays * 86400000L;
        File[] files = trash.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.lastModified() >= cutoff) continue;
            deleteRecursiveQuietly(file);
        }
    }

    private void deleteRecursiveQuietly(File file) {
        if (file == null || !file.exists() || Files.isSymbolicLink(file.toPath())) return;
        try {
            deleteRecursive(file);
        } catch (Exception ignored) {
        }
    }

    private JSONObject pathResult(File file,String action) throws Exception {
        JSONObject result = new JSONObject(true);
        result.put("path",relative(file));
        result.put("action",action);
        return result;
    }

    private void rejectInternalNamespace(File file) throws Exception {
        if (isInternalNamespace(file)) {
            throw new IllegalArgumentException("内部历史与回收站目录不能直接操作");
        }
    }

    private void rejectNestedTarget(File source,File target) throws Exception {
        if (source == null || target == null || !source.isDirectory()) return;
        String sourcePath = source.getCanonicalPath();
        String targetPath = target.getCanonicalPath();
        if (targetPath.startsWith(sourcePath+File.separator)) {
            throw new IllegalArgumentException("目标路径不能位于源目录内部");
        }
    }

    private boolean isInternalNamespace(File file) throws Exception {
        if (file == null) return false;
        File root = root().getCanonicalFile();
        String relative = root.toPath().relativize(file.getCanonicalFile().toPath())
                .toString().replace("\\","/");
        return relative.equals(TRASH) || relative.startsWith(TRASH+"/")
                || relative.equals(HISTORY) || relative.startsWith(HISTORY+"/");
    }

    private long directorySize(File file) {
        if (file == null || !file.exists() || Files.isSymbolicLink(file.toPath())) return 0L;
        if (file.isFile()) return file.length();
        long total = 0L;
        File[] files = file.listFiles();
        if (files != null) {
            for (File child : files) total += directorySize(child);
        }
        return total;
    }

    private String relative(File file) throws Exception {
        return root().getCanonicalFile().toPath().relativize(file.getCanonicalFile().toPath())
                .toString().replace("\\","/");
    }
}
