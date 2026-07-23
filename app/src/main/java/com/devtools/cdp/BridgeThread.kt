package com.devtools.cdp

import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BridgeThread：双向 byte 拷贝（socat 语义）。
 *
 * 一条 TCP 连接（来自主进程 OkHttp 访问 127.0.0.1:9222）↔ 一条 abstract socket fd
 * （连到 Chrome devtools）。开两条线程各负责一个方向的字节流搬运。
 *
 * UserService 进程内不弹 UI、不缓存普通 Context；只做桥接。
 */
object BridgeThread {

    /**
     * 把一条 TCP Socket 与一条 native 拿到的 FileDescriptor 双向桥接。
     * 阻塞直到任一方向 EOF 或异常。返回后调用方负责关闭两端。
     */
    fun bridge(tcp: Socket, fd: FileDescriptor) {
        val running = AtomicBoolean(true)

        val fdIn: InputStream = FileInputStream(fd)
        val fdOut: OutputStream = FileOutputStream(fd)
        val tcpIn = tcp.getInputStream()
        val tcpOut = tcp.getOutputStream()

        val t1 = Thread({
            pipe(tcpIn, fdOut, running)
            running.set(false)
            silentClose(fdOut)
            silentClose(tcpIn)
        }, "cdp-bridge-tcp2fd")

        val t2 = Thread({
            pipe(fdIn, tcpOut, running)
            running.set(false)
            silentClose(tcpOut)
            silentClose(fdIn)
        }, "cdp-bridge-fd2tcp")

        t1.start()
        t2.start()
        try { t1.join() } catch (_: InterruptedException) {}
        try { t2.join() } catch (_: InterruptedException) {}
    }

    /** 单向拷贝：从 in 读，写到 out，直到 EOF / 异常 / running=false。 */
    private fun pipe(input: InputStream, output: OutputStream, running: AtomicBoolean) {
        val buf = ByteArray(8 * 1024)
        try {
            while (running.get()) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                output.write(buf, 0, n)
                output.flush()
            }
        } catch (_: Throwable) {
            // 任意方向断开即结束整条桥接
        }
    }

    private fun silentClose(c: java.io.Closeable) {
        try { c.close() } catch (_: Throwable) {}
    }
}
