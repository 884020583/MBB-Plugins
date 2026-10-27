package org.moboxlab.mbb.eval.bank;

import java.io.IOException;

import org.moboxlab.moboxbot.API.Plugin;

/**
 * JAR 内置资源读取接口（B-2）。
 *
 * <p>释放内置题库时只依赖这一层，便于单测注入伪造资源；
 * 生产环境用 {@link #of(Plugin)} 包装插件 API，
 * 避免 bank 包直接依赖具体读取实现。</p>
 */
public interface EvalBankResources {

    /**
     * 读取 JAR 内资源。
     *
     * @param resourcePath 资源路径，例如 bank/bank-1.0.0.json
     * @return 资源字节；不存在时返回 null
     * @throws IOException 读取失败
     */
    byte[] read(String resourcePath) throws IOException;

    /** 用插件 API 的 readResource 构造资源读取器。 */
    static EvalBankResources of(final Plugin plugin) {
        return new EvalBankResources() {
            @Override
            public byte[] read(String resourcePath) throws IOException {
                if (plugin == null) {
                    throw new IOException("插件实例为空，无法读取内置题库资源");
                }
                try {
                    return plugin.readResource(resourcePath);
                } catch (RuntimeException e) {
                    throw new IOException("读取内置题库资源失败：" + resourcePath, e);
                }
            }
        };
    }
}
