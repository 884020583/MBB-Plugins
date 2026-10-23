package org.moboxlab.mbb.roleplay;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.moboxlab.moboxbot.API.Plugin;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 角色私有工作区。
 *
 * 所有路径都必须落在插件数据目录下的 data/ 中，拒绝绝对路径、父目录逃逸和符号链接。
 */
public class RoleplayWorkspaceService {
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

    public JSONObject write(String path,String content,boolean append) throws Exception {
        if (!config.workspaceEnable) throw new IllegalArgumentException("角色工作区未启用");
        File target = resolve(path,false);
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
        Files.write(target.toPath(),bytes);
        JSONObject result = new JSONObject(true);
        result.put("path",relative(target));
        result.put("size",target.length());
        result.put("append",append);
        return result;
    }

    public JSONObject delete(String path,boolean recursive) throws Exception {
        if (!config.workspaceEnable) throw new IllegalArgumentException("角色工作区未启用");
        File target = resolve(path,false);
        if (!target.exists()) throw new IllegalArgumentException("路径不存在");
        rejectSymlink(target);
        if (target.isDirectory()) {
            File[] files = target.listFiles();
            if (files != null && files.length > 0 && !recursive) {
                throw new IllegalArgumentException("文件夹非空；确认删除请传 recursive=true");
            }
            deleteRecursive(target);
        } else if (!target.delete()) {
            throw new IllegalStateException("删除文件失败");
        }
        return new JSONObject(true);
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
