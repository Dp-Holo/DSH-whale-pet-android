package com.dsha.whalepet;

/**
 * Shizuku UserService 接口：在 Shizuku 独立进程（shell 权限）执行命令。
 */
interface IShizukuCommand {
    /** 执行命令，返回退出码。 */
    int exec(in String[] args) = 1;

    /**
     * 执行命令并返回诊断文本："exit=<code> | out: ... | err: ..."
     * 用于把失败原因带回界面，避免只拿到退出码却不知为何失败。
     */
    String run(in String[] args) = 2;

    /**
     * 服务销毁（Shizuku 调用，需退出进程）。
     * transaction code 须固定为 16777114（Shizuku 官方约定）。
     */
    void destroy() = 16777114;
}
