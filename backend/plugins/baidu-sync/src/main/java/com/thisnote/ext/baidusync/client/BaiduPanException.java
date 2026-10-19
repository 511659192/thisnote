package com.thisnote.ext.baidusync.client;

public class BaiduPanException extends IllegalStateException {

    private final int errno;

    public BaiduPanException(int errno) {
        super("百度网盘文件接口返回错误码 " + errno);
        this.errno = errno;
    }

    public BaiduPanException(int apiErrno,
                             int itemErrno,
                             String path) {
        super(message(apiErrno, itemErrno, path));
        this.errno = itemErrno == 0 ? apiErrno : itemErrno;
    }

    public int errno() {
        return errno;
    }

    private static String message(int apiErrno,
                                  int itemErrno,
                                  String path) {
        String message = apiErrno == 0
                ? "百度网盘单文件操作错误码 " + itemErrno
                : "百度网盘文件接口返回错误码 " + apiErrno;
        if (itemErrno != 0 && apiErrno != 0) {
            message += "，单文件错误码 " + itemErrno;
        }
        return path == null || path.isBlank() ? message : message + "，路径：" + path;
    }
}
