package test.whip

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.HardwareVideoEncoderFactory
import org.webrtc.IceCandidate
import org.webrtc.Logging
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack
import org.webrtc.WrappedVideoDecoderFactory
import java.net.HttpURLConnection
import java.net.URL

data class WHIPClientConfig(
    val context: Context,
    val eglBase: EglBase,
    val endpoint: String,
    val svr: SurfaceViewRenderer,
    val usbCameraMode: Boolean = false
)

class WHIPClient(private val config: WHIPClientConfig) {
    private var peerConnection: PeerConnection? = null
    private var iceGatheringComplete = CompletableDeferred<String?>()
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    private var cameraVideoCapture: CameraVideoCapturer? = null
    private var usbVideoCapturer: UsbCapturer? = null
    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var localAudioTrack: AudioTrack? = null
    private var localVideoTrack: VideoTrack? = null
    private lateinit var peerConnectionFactory: PeerConnectionFactory

    init {
        println("Initializing PeerConnection")
        initializePeerConnection()
    }

    private fun initializePeerConnection() {
        // Initialize WebRTC
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(config.context)
                .createInitializationOptions()
        )
//        val encoderFactory = DefaultVideoEncoderFactory(config.eglBase.eglBaseContext, false, false)
        // 当华为设备出错时，可使用此方法，详见注1
        val encoderFactory = HardwareVideoEncoderFactory(config.eglBase.eglBaseContext, true, true)
        val decoderFactory = WrappedVideoDecoderFactory(config.eglBase.eglBaseContext)
        // Create a PeerConnectionFactory
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(PeerConnectionFactory.Options())
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()


        // Create PeerConnection.RTCConfiguration with STUN server
//        val defaultIceServers = listOf(
//            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer()
//        )
//        val iceServers = config.iceServers.takeUnless { it.isNullOrEmpty() } ?: defaultIceServers
        val rtcConfig = PeerConnection.RTCConfiguration(ArrayList()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            enableCpuOveruseDetection = false
        }

        Logging.enableLogToDebugOutput(Logging.Severity.LS_WARNING)


        // Create Audio Source
        val audioSource = peerConnectionFactory.createAudioSource(createAudioConstraints())
        localAudioTrack = peerConnectionFactory.createAudioTrack("101", audioSource)

        // Create Video Capturer
        val videoCapturer = if (config.usbCameraMode) {
            usbVideoCapturer = UsbCapturer(config.context, config.svr) // usb camera
            usbVideoCapturer
        } else {
            cameraVideoCapture = createCameraVideoCapturer(config.context) // phone camera
            cameraVideoCapture
        }
        // Create Video Source
        val videoSource = peerConnectionFactory.createVideoSource(false)
        // Create Video Track
        localVideoTrack = peerConnectionFactory.createVideoTrack("100", videoSource).apply {
            addSink(config.svr)
        }
        surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", null)
        videoCapturer?.initialize(surfaceTextureHelper, config.context, videoSource.capturerObserver)
        videoCapturer?.startCapture(640, 480, 30)

        // Create PeerConnection
        println("Create PeerConnection")
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {

            override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState?) {
                println("onIceGatheringChange: $iceGatheringState")
                if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                    iceGatheringComplete.complete(peerConnection?.localDescription?.description)
                }
            }

            override fun onTrack(rtpTransceiver: RtpTransceiver?) {
                println("onTrack: $rtpTransceiver")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                // Handle connection state change
                println("onConnectionChange: $newState")
            }

            override fun onSignalingChange(signalingState: PeerConnection.SignalingState?) {
                println("onSignalingChange: $signalingState")
            }

            override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState?) {
                println("onIceConnectionChange: $iceConnectionState")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                println("onIceCandidatesRemoved")
                peerConnection!!.removeIceCandidates(candidates)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                println("onIceConnectionReceivingChange")
            }

            override fun onIceCandidate(candidate: IceCandidate?) {
                println("onIceCandidate: $candidate")
                peerConnection!!.addIceCandidate(candidate)
            }

            override fun onAddStream(stream: MediaStream?) {
                println("onAddStream: $stream, videoTracks: ${stream?.videoTracks?.size}, audioTracks: ${stream?.audioTracks?.size}")
            }

            override fun onRemoveStream(stream: MediaStream?) {
                println("onRemoveStream: $stream")
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                println("onDataChannel: $dataChannel")
            }

            override fun onRenegotiationNeeded() {
                println("onRenegotiationNeeded")
                coroutineScope.launch {
                    negotiateConnectionWithClientOffer()
                }
            }

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                println("onAddTrack: ${mediaStreams?.size}")
            }
        }).apply {
            localAudioTrack?.let {
                this?.addTransceiver(
                    it,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }
            localVideoTrack?.let {
                this?.addTransceiver(
                    it,
                    RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_ONLY)
                )
            }
        }
    }

    private fun createAudioConstraints(): MediaConstraints {
        val audioConstraints = MediaConstraints()
        // 回声消除
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
        // 自动增益
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
        // 高音过滤
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
        // 噪音处理
        audioConstraints.mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        return audioConstraints
    }


    private suspend fun waitToCompleteICEGathering(): String? {
        return try {
            withTimeoutOrNull(1000) {
                iceGatheringComplete.await()
            } ?: peerConnection?.localDescription?.description
        } catch (e: Exception) {
            peerConnection?.localDescription?.description
        }
    }

    private suspend fun negotiateConnectionWithClientOffer(): String? {
        println("Negotiating connection")
        val offerCreationDeferred = CompletableDeferred<SessionDescription>()
        val setLocalDescDeferred = CompletableDeferred<Unit>()
        val setRemoteDescDeferred = CompletableDeferred<Unit>()

        // Create Offer
        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                sessionDescription?.let {
                    offerCreationDeferred.complete(it)
                }
            }

            override fun onSetSuccess() {}
            override fun onCreateFailure(s: String?) {
                offerCreationDeferred.completeExceptionally(Exception(s))
            }

            override fun onSetFailure(s: String?) {}
        }, MediaConstraints())

        println("Creating Offer")
        val offer = offerCreationDeferred.await()
        println("\n\nOffer created, offer ------> \n${offer.description}")

        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                setLocalDescDeferred.complete(Unit)
            }

            override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
            override fun onCreateFailure(s: String?) {}
            override fun onSetFailure(s: String?) {
                setLocalDescDeferred.completeExceptionally(Exception(s))
            }
        }, offer)

        println("Setting local description")
        setLocalDescDeferred.await()
        println("Local description set")

        println("Gathering ICE candidates")
        val initialisedOffer = waitToCompleteICEGathering()
            ?: throw Exception("Failed to gather ICE candidates for offer")

        println("Gathering ICE candidates complete")
        println("Exchanging offer")

        val resultDeferred = CompletableDeferred<String?>()
        coroutineScope.launch(Dispatchers.IO) {
            val response = withContext(Dispatchers.IO + CoroutineExceptionHandler { coroutineContext, exception ->
                println("Handle $exception in CoroutineExceptionHandler")
            }) {
                postSDPOffer(config.endpoint, initialisedOffer)
            }
            println("\npostSDPOffer Response: \n---> ${response.responseCode}, ${response.requestMethod}")

            when (response.responseCode) {
                201 -> {
                    val answerSDP = withContext(Dispatchers.IO) {
                        response.inputStream.bufferedReader().use { it.readText() }
                    }

                    println("\n\nanswerSDP ------> \n$answerSDP")
                    withContext(Dispatchers.Main) {
                        peerConnection?.setRemoteDescription(
                            object : SdpObserver {
                                override fun onSetSuccess() {
                                    println("成功设置远程SDP")
                                    setRemoteDescDeferred.complete(Unit)
                                }

                                override fun onCreateSuccess(sessionDescription: SessionDescription?) {}
                                override fun onCreateFailure(s: String?) {}
                                override fun onSetFailure(s: String?) {
                                    println("处理设置远程SDP失败: $s")
                                    setRemoteDescDeferred.completeExceptionally(Exception(s))
                                }
                            },
                            SessionDescription(SessionDescription.Type.ANSWER, answerSDP)
                        )
                    }

                    println("Answer received, setting remote description")
                    setRemoteDescDeferred.await() // Wait for the remote description to be set
                    println("Answer set")
                    resultDeferred.complete(response.getHeaderField("Location"))
                }

                403 -> {
                    println("Token is invalid")
                    throw Error("Unauthorized")
                }

                405 -> {
                    println("Must be returned for future WHEP spec updates")
                }

                else -> {
                    val errorMessage =
                        response.errorStream.bufferedReader().use { it.readText() }
                    println("error: $errorMessage")
                }
            }

        }
        return resultDeferred.await()
    }

    private fun postSDPOffer(endpoint: String, data: String): HttpURLConnection {
//        val endpoint = e+"\na=extmap:4 urn:3gpp:video-orientation"
        println("\n\npostSDPOffer endpoint(sdp) ------> \n${data}")
        val url = URL(endpoint)
        val connection = url.openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Content-Type", "application/sdp")
        connection.doOutput = true
        connection.outputStream.use { os ->
            os.write(data.toByteArray())
        }
        return connection

//        val url = URL(endpoint)
//        val connection = url.openConnection() as HttpsURLConnection // 使用HttpsURLConnection
//        connection.requestMethod = "POST"
//        connection.setRequestProperty("Content-Type", "application/sdp")
//        connection.doOutput = true
//
//        // 创建信任所有证书的TrustManager
//        val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
//            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
//            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
//            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
//        })
//
//        // 使用信任所有证书的TrustManager创建SSL上下文
//        val sslContext = SSLContext.getInstance("TLS")
//        sslContext.init(null, trustAllCerts, SecureRandom())
//
//        // 设置SSL上下文到连接中
//        connection.sslSocketFactory = sslContext.socketFactory
//        connection.hostnameVerifier = HostnameVerifier { _, _ -> true } // 设置主机名验证器为接受所有主机名
//
//        connection.outputStream.use { os ->
//            os.write(data.toByteArray())
//        }
//        return connection
    }

    fun cleanup() {
        surfaceTextureHelper?.dispose()
        if (config.usbCameraMode) {
            usbVideoCapturer?.dispose()
        } else {
            config.svr.release()
            cameraVideoCapture?.dispose()
        }
        localVideoTrack?.dispose()
        localAudioTrack?.dispose()

        peerConnection?.close()
        peerConnectionFactory.dispose()
        config.eglBase.releaseSurface()
        config.eglBase.release()
        coroutineScope.cancel()
    }

    private fun createCameraVideoCapturer(context: Context): CameraVideoCapturer {
        val enumerator = Camera2Enumerator(context)
        val deviceNames = enumerator.deviceNames
        for (deviceName in deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                val videoCapturer = enumerator.createCapturer(deviceName, null)
                if (videoCapturer != null) {
                    return videoCapturer
                }
            }
        }
        throw IllegalStateException("No front facing camera found.")
    }
}


// 注1：华为设备可能会出现编码错误的情况，按下述方法进行兼容性处理
/*
找到webrtc源码里的HardwareVideoEncoderFactory和HardwareVideoEncoder
复制一份自定义个名字，包名要和源码一样，修改如下代码，
将HardwareVideoEncoderFactory里用到HardwareVideoEncoder都改成自定义的，
setVideoEncoderFactory()改为自定义的HardwareVideoEncoderFactory
 */
if (outputBuilders.size() > MAX_ENCODER_Q_SIZE) {
    ...
    VideoCodecStatus status = resetCodec(frameWidth, frameHeight, shouldUseSurfaceMode);
    if (status != VideoCodecStatus.OK) {
        return status;
    }
    return VideoCodecStatus.NO_OUTPUT;
}
