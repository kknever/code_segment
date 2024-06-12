package test.whip

import android.content.Context
import android.hardware.usb.UsbDevice
import com.serenegiant.usb.IFrameCallback
import com.serenegiant.usb.USBMonitor
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener
import com.serenegiant.usb.USBMonitor.UsbControlBlock
import com.serenegiant.usb.UVCCamera
import org.webrtc.CapturerObserver
import org.webrtc.NV21Buffer
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.Executors

// usb camera，依赖 https://github.com/saki4510t/UVCCamera
class UsbCapturer(context: Context, private val svVideoRender: SurfaceViewRenderer) : VideoCapturer,
    OnDeviceConnectListener, IFrameCallback {
    private lateinit var monitor: USBMonitor
    private var capturerObserver: CapturerObserver? = null
    private val executor: Executor = Executors.newSingleThreadExecutor()

    var camera: UVCCamera? = null

    init {
        executor.execute(Runnable {
            monitor = USBMonitor(context, this@UsbCapturer)
            monitor.register()
            camera = UVCCamera()
        })
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        context: Context,
        capturerObserver: CapturerObserver
    ) {
        this.capturerObserver = capturerObserver
    }

    override fun startCapture(i: Int, i1: Int, i2: Int) {
        println("UsbCapturer startCapture")
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        println("UsbCapturer stopCapture: $camera")
        camera!!.stopPreview()
        camera!!.close()
    }

    override fun changeCaptureFormat(i: Int, i1: Int, i2: Int) {
        println("UsbCapturer changeCaptureFormat: ")
    }

    override fun dispose() {
        println("UsbCapturer dispose")
        camera?.stopCapture()
        camera!!.destroy()
        monitor.unregister()
        monitor.destroy()
        svVideoRender.release()
    }

    override fun isScreencast(): Boolean {
        return false
    }

    override fun onAttach(device: UsbDevice) {
        println("UsbCapturer onAttach: $device")
        monitor.requestPermission(device)
    }

    override fun onDettach(device: UsbDevice) {
        println("UsbCapturer onDettach")
    }

    override fun onConnect(device: UsbDevice, ctrlBlock: UsbControlBlock, createNew: Boolean) {
        Timber.d("UsbCapturer onConnect: $device")
        executor.execute(Runnable {
            camera!!.open(ctrlBlock)
            try {
                camera!!.setPreviewSize(
                    UVCCamera.DEFAULT_PREVIEW_WIDTH,
                    UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                    UVCCamera.FRAME_FORMAT_MJPEG
                )
            } catch (e: IllegalArgumentException) {
                try {
                    camera!!.setPreviewSize(
                        UVCCamera.DEFAULT_PREVIEW_WIDTH,
                        UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                        UVCCamera.DEFAULT_PREVIEW_MODE
                    )
                } catch (e1: IllegalArgumentException) {
                    camera!!.destroy()
                    camera = null
                }
            }
            camera!!.setPreviewDisplay(svVideoRender.holder)
            camera!!.setFrameCallback(this@UsbCapturer, UVCCamera.PIXEL_FORMAT_YUV420SP)
            camera!!.startPreview()
        })

    }

    override fun onDisconnect(device: UsbDevice, ctrlBlock: UsbControlBlock) {
        println("UsbCapturer onDisconnect: $device")
    }

    override fun onCancel(device: UsbDevice) {
        println("UsbCapturer onCancel: $device")
    }

    override fun onFrame(frame: ByteBuffer) {
        executor.execute(Runnable {
            val imageArray = ByteArray(frame.remaining())
            frame[imageArray]
            val mNV21Buffer = NV21Buffer(
                imageArray,
                UVCCamera.DEFAULT_PREVIEW_WIDTH,
                UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                null
            )
            val mVideoFrame = VideoFrame(mNV21Buffer, 0, System.nanoTime())
            capturerObserver!!.onFrameCaptured(mVideoFrame)
        })
    }
}
