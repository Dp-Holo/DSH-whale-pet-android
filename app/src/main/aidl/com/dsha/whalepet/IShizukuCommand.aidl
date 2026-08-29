package com.dsha.whalepet;

/**
 * Shizuku UserService 接口：在 Shizuku 独立进程（shell 权限）执行命令。
 */
interface IShizukuCommand {
    /** 执行命令，返回退出码。 */
    int exec(String[] args);
}
