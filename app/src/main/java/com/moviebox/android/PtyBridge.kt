package com.moviebox.android

object PtyBridge {
    init {
        System.loadLibrary("pty_bridge")
    }

    external fun startProcess(
        binaryPath: String,
        homeDir: String,
        binDir: String,
        rows: Int,
        cols: Int
    ): IntArray?

    external fun readOutput(fd: Int): ByteArray?
    external fun writeInput(fd: Int, data: ByteArray)
    external fun resizePty(fd: Int, rows: Int, cols: Int)
    external fun closePty(fd: Int, pid: Int)
}
