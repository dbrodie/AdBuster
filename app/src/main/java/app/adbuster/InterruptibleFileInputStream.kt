package app.adbuster

import java.io.FileDescriptor
import java.io.FileInputStream
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd

class InterruptibleFileInputStream : FileInputStream {
    private var mInterruptFd : FileDescriptor? = null
    private var mBlockFd : FileDescriptor? = null

    enum class BlockInterrupt {
        OK, INTERRUPT
    }

    class InterruptedStreamException: Exception() {}

    constructor(fd: FileDescriptor): super(fd) {

        val pipes = Os.pipe()
        mInterruptFd = pipes[0]
        mBlockFd = pipes[1]
    }

    private fun blockRead() : BlockInterrupt {
        val mainFd = StructPollfd()
        mainFd.fd = fd
        mainFd.events = OsConstants.POLLIN.toShort()
        val blockFd = StructPollfd()
        blockFd.fd = mBlockFd
        blockFd.events = OsConstants.POLLHUP.or(OsConstants.POLLERR).toShort()

        val pollArray = arrayOf(mainFd, blockFd)
        // TODO Handle EINTER (the irony...)
        Os.poll(pollArray, -1)

        if (pollArray[1].revents != 0.toShort()) {
            return BlockInterrupt.INTERRUPT
        } else {
            return BlockInterrupt.OK
        }
    }

    override fun read(buffer: ByteArray?): Int {
        if (blockRead() == BlockInterrupt.INTERRUPT) {
            throw InterruptedStreamException()
        }
        return super.read(buffer)
    }

    fun interrupt() {
        if (mInterruptFd != null) {
            Os.close(mInterruptFd)
            mInterruptFd = null
        }
    }
}