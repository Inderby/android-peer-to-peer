package com.example.claude
// MainActivity.kt
import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray

import org.json.JSONObject
import org.webrtc.*
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {
    private lateinit var peerConnectionFactory: PeerConnectionFactory
    private lateinit var localVideoView: SurfaceViewRenderer
    private lateinit var remoteVideoView: SurfaceViewRenderer
    private lateinit var statusText: TextView
    private lateinit var callButton: Button
    private lateinit var endCallButton: Button
    private lateinit var eglBase: EglBase
    private var isEndingCall = AtomicBoolean(false)  // 통화 종료 진행 중인지 확인하는 플래그
    private lateinit var localVideoTrack: VideoTrack
    private lateinit var localAudioTrack: AudioTrack
    private var remoteVideoTrack: VideoTrack? = null  // 추가
    private var remoteAudioTrack: AudioTrack? = null  // 추가
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null

    private var targetUserId: String? = null
    private var myId: String? = null
    private var isInCall = false
    private var isNegotiating = false
    private val socketHandler = SocketHandler.getInstance()
    private var state = CallState.IDLE
    private var isInitiator = false
    private var isPeerConnectionCreated = false
    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    private lateinit var userListSpinner: Spinner
    private val userList = mutableListOf<String>()
    private val spinnerAdapter by lazy {
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, userList)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100  // 임의의 정수값
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        eglBase = EglBase.create()

        if (!checkPermissions()) {
            requestPermissions()
        } else {
            initialize()
        }

        if (eglBase == null || eglBase.eglBaseContext == null) {
            Log.e("WebRTC", "EGL context is null")
            return
        }
        myId = getDeviceUuid()
//        Logging.enableLogToDebugOutput(Logging.Severity.LS_VERBOSE);

        if(!checkPermissions()){
            Log.e("PERMISSION", "Permission not satisfied")
        }
        if(!isPeerConnectionFactoryInitialized()){
            Log.e("PeerConnectionFactory", "Not initialized")
        }

    }

    @SuppressLint("HardwareIds")
    private fun getDeviceUuid(): String? {
        return Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }



    private fun isPeerConnectionFactoryInitialized(): Boolean {
        return this::peerConnectionFactory.isInitialized &&
                this::localAudioTrack.isInitialized &&
                this::localVideoTrack.isInitialized
    }



    private fun initialize() {
        setupViews()
        initializeWebRTC()
        connectToSignalingServer()
    }

    private fun setupViews() {
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        statusText = findViewById(R.id.statusText)
        callButton = findViewById(R.id.callButton)
        endCallButton = findViewById(R.id.endCallButton)


        localVideoView.init(eglBase.eglBaseContext, null)
        localVideoView.setEnableHardwareScaler(true)
        localVideoView.setMirror(true)
        remoteVideoView.init(eglBase.eglBaseContext, null)
        remoteVideoView.setEnableHardwareScaler(true)
        remoteVideoView.setMirror(false)

        userListSpinner = findViewById(R.id.userListSpinner)
        userListSpinner.adapter = spinnerAdapter

        userListSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                targetUserId = userList[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                targetUserId = null
            }
        }

        callButton.setOnClickListener {
            targetUserId?.let { userId ->
                when {
                    !isInCall && state != CallState.RECEIVING_CALL -> {
                        // 발신 시작
                        startCall(userId)
                    }

                    state == CallState.RECEIVING_CALL -> {
                        // 수신 수락
                        acceptCall(userId)
                    }
                }
            } ?: run {
                showToast("통화할 사용자를 선택해주세요")
            }
        }

        endCallButton.setOnClickListener {
            endCall()
        }

        updateUIState(CallState.IDLE)
    }


    private fun acceptCall(callerId: String) {
        isInitiator = false
        isNegotiating = false  // 초기화
        createAndInitializePeerConnection()
        Handler(Looper.getMainLooper()).postDelayed({
            if (peerConnection != null && isPeerConnectionCreated) {
                socketHandler.sendCallAccept(callerId)
                updateUIState(CallState.IN_CALL)
            } else {
                Log.e("WebRTC", "Failed to initialize peer connection")
                showToast("연결 설정에 실패했습니다")
                endCall()
            }
        }, 1000)
    }

    private fun createAndInitializePeerConnection() {
        if (!isPeerConnectionFactoryInitialized()) {
            Log.e("WebRTC", "PeerConnectionFactory is not initialized")
            initializeWebRTC()
        }

        if (peerConnection == null) {
            createPeerConnection()
        }

        if (peerConnection == null) {
            Log.e("WebRTC", "Failed to create PeerConnection")
            return
        }
    }

    private fun connectToSignalingServer() {
        val userId = UUID.randomUUID().toString()
        socketHandler.init("http://10.0.2.2:3000")
        socketHandler.connect(userId) { success ->
            runOnUiThread {
                if (success) {
                    statusText.text = "서버에 연결됨"
                    registerCallEvents()
                } else {
                    statusText.text = "서버 연결 실패"
                }
            }
        }
    }

    private fun registerCallEvents() {
        socketHandler.registerCallEvents(
            onCallReceived = { callerId ->
                Log.d("SDP", "Call received from: $callerId")
                runOnUiThread {
                    targetUserId = callerId
                    updateUIState(CallState.RECEIVING_CALL)
                }
            },
            onCallAccepted = { accepterId ->
                Log.d("SDP", "Call accepted by: $accepterId")
                runOnUiThread {
                    if (isInitiator) {  // 통화 시작자만 offer를 생성
                        isNegotiating = false  // 협상 상태 초기화
                        createPeerConnection()
                        createAndSendOffer()
                        updateUIState(CallState.IN_CALL)
                    }
                }
            },
            onCallRejected = { rejecterId ->
                runOnUiThread {
                    updateUIState(CallState.IDLE)
                    showToast("통화가 거절되었습니다")
                }
            },
            onCallEnded = { enderId ->
                runOnUiThread {
                    endCall()
                }
            },
            onOffer = { callerId, offerSdp ->
                Log.d("SDP", "Received Offer SDP from $callerId:")
                Log.d("SDP", "Offer SDP: $offerSdp")
                runOnUiThread {
                    handleOffer(callerId, offerSdp)
                }
            },
            onAnswer = { answererId, answerSdp ->
                Log.d("SDP", "Received Answer SDP from $answererId:")
                Log.d("SDP", "Answer SDP: $answerSdp")
                runOnUiThread {
                    handleAnswer(answerSdp)
                }
            },
            onIceCandidate = { senderId, candidateJson ->
                Log.d("SDP", "Received ICE candidate from $senderId:")
                Log.d("SDP", "ICE candidate: $candidateJson")
                runOnUiThread {
                    handleIceCandidate(candidateJson)
                }
            }
        )
        socketHandler.socket?.on("userList") { args ->
            val users = (args[0] as JSONArray).let { array ->
                List(array.length()) { i -> array.getString(i) }
            }
            runOnUiThread {
                userList.clear()
                userList.addAll(users.filter { it != socketHandler.getUserId() }) // 자신을 제외한 사용자만 표시
                spinnerAdapter.notifyDataSetChanged()
            }
        }

        // 사용자 연결 해제 이벤트 추가
        socketHandler.socket?.on("user-disconnected") { args ->
            val disconnectedUserId = args[0] as String
            runOnUiThread {
                if (targetUserId == disconnectedUserId) {
                    endCall()
                    showToast("상대방이 연결을 종료했습니다")
                }
                userList.remove(disconnectedUserId)
                spinnerAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun startCall(targetUserId: String) {
        isInitiator = true
        createAndInitializePeerConnection() // PeerConnection 먼저 생성
        Handler(Looper.getMainLooper()).postDelayed({
            if (peerConnection != null && isPeerConnectionCreated) {
                socketHandler.sendCallRequest(targetUserId)
                updateUIState(CallState.CALLING)
            } else {
                Log.e("WebRTC", "Failed to initialize peer connection")
                showToast("연결 설정에 실패했습니다")
                cleanupAndEndCall()
            }
        }, 1000) // 1초 지연으로 초기화 완료 보장
    }

    private fun handleOffer(callerId: String, offerSdp: String) {
        Log.d("WebRTC", "Received Offer SDP: $offerSdp")

        if (!isPeerConnectionCreated) {
            createPeerConnection()
        }
        if (peerConnection == null) {
            Log.e("WebRTC", "PeerConnection is null when handling offer")
            createPeerConnection()
        }

        if (isNegotiating || peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.d("WebRTC", "Ignoring offer - already negotiating or have local offer")
            return
        }

        val sessionDescription = SessionDescription(
            SessionDescription.Type.OFFER,
            offerSdp
        )

        isNegotiating = true
        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAndSendAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e("WebRTC", "Failed to set remote description: $error")
                isNegotiating = false

                runOnUiThread {
                    showToast("통화 연결에 실패했습니다")
                    endCall()
                }
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    private fun handleAnswer(answerSdp: String) {
        val sessionDescription = SessionDescription(
            SessionDescription.Type.ANSWER,
            answerSdp
        )

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onSetSuccess() {
                Log.d("WebRTC", "Remote description set successfully")
                isNegotiating = false  // 협상 완료
            }
            override fun onCreateFailure(p0: String?) {}
            override fun onSetFailure(p0: String?) {
                Log.e("WebRTC", "Failed to set remote description: $p0")
                isNegotiating = false
            }
        }, sessionDescription)
    }

    private fun handleIceCandidate(candidateJson: String) {
        try {
            val json = JSONObject(candidateJson)
            val candidate = IceCandidate(
                json.getString("sdpMid"),
                json.getInt("sdpMLineIndex"),
                json.getString("candidate")
            )
            peerConnection?.addIceCandidate(candidate)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun preferCodec(sdpDescription: String, codec: String, isVideo: Boolean): String {
        val lines = sdpDescription.split("\r\n")
        val sdpBuilder = StringBuilder()
        val mediaType = if (isVideo) "video" else "audio"
        var mediaDescription = false
        var rtpmapId = ""
        val codecRtpmaps = mutableListOf<String>()

        lines.forEach { line ->
            if (line.startsWith("m=$mediaType")) {
                mediaDescription = true
            }

            if (mediaDescription && line.startsWith("a=rtpmap")) {
                if (line.contains(codec, ignoreCase = true)) {
                    rtpmapId = line.split(" ")[0].split(":")[1]
                    codecRtpmaps.add(0, line)
                } else {
                    codecRtpmaps.add(line)
                }
                return@forEach
            }

            if (!line.startsWith("a=rtpmap")) {
                sdpBuilder.append(line).append("\r\n")
            }
        }

        codecRtpmaps.forEach { rtpmap ->
            sdpBuilder.append(rtpmap).append("\r\n")
        }

        return sdpBuilder.toString()
    }

    private fun createAndSendOffer() {
        Log.d("WebRTC", "Attempting to create and send offer. isNegotiating: $isNegotiating")
        if (peerConnection == null) {
            Log.e("WebRTC", "PeerConnection is null when trying to create offer")
            createAndInitializePeerConnection() // PeerConnection 재생성 시도

            if (peerConnection == null) {
                Log.e("WebRTC", "Failed to create PeerConnection after retry")
                isNegotiating = false
                showToast("연결 설정에 실패했습니다")
                return
            }
        }
        synchronized(this) {
            if (isNegotiating) {
                Log.d("WebRTC", "Skipping create offer - already negotiating")
                return
            }
            isNegotiating = true
        }

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        try {
            peerConnection?.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sessionDescription: SessionDescription) {
                    Log.d("WebRTC", "Offer created successfully")
                    var sdpDescription = sessionDescription.description
                    sdpDescription = modifySdp(sdpDescription)

                    val modifiedSdp = SessionDescription(sessionDescription.type, sdpDescription)
                    // Local Description 설정
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            Log.d("WebRTC", "Local description set successfully")
                            targetUserId?.let { userId ->
                                socketHandler.sendOffer(userId, modifiedSdp.description)
                                Log.d("WebRTC", "Offer sent to $userId")
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            Log.e("WebRTC", "Failed to set local description: $error")
                            isNegotiating = false
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {}
                        override fun onCreateFailure(p0: String?) {}
                    }, modifiedSdp)
                }

                override fun onCreateFailure(error: String?) {
                    Log.e("WebRTC", "Offer creation failed: $error")
                    isNegotiating = false
                }

                override fun onSetSuccess() {}
                override fun onSetFailure(error: String?) {
                    Log.e("WebRTC", "Offer set failed: $error")
                    isNegotiating = false
                }
            }, mediaConstraints)
        } catch (e: Exception) {
            Log.e("WebRTC", "Exception during offer creation", e)
            isNegotiating = false
        }
    }

    private fun createAndSendAnswer() {
        Log.d("WebRTC", "Creating answer")
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }
        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        targetUserId?.let { userId ->
                            socketHandler.sendAnswer(userId, sessionDescription.description)
                        }
                        isNegotiating = false
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onSetFailure(p0: String?) {
                        isNegotiating = false
                    }
                    override fun onCreateFailure(p0: String?) {
                        isNegotiating = false
                    }
                }, sessionDescription)
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {
                isNegotiating = false
            }
            override fun onCreateFailure(p0: String?) {
                isNegotiating = false
            }
        }, mediaConstraints)
    }

    private fun endCall() {
        // 이미 종료 중이면 무시
        if (!isEndingCall.compareAndSet(false, true)) {
            Log.d("WebRTC", "Call ending already in progress")
            return
        }

        try {
            Log.d("WebRTC", "Starting call end process")

            if (!isInCall) {
                Log.d("WebRTC", "Call is not active, skipping end call process")
                return
            }

            // 상대방에게 통화 종료 알림은 한 번만 보냄
            targetUserId?.let { userId ->
                socketHandler.sendEndCall(userId)
                Log.d("WebRTC", "End call signal sent to: $userId")
            }

            runOnUiThread {
                cleanupResources()
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "Error during end call", e)
        } finally {
            isEndingCall.set(false)  // 플래그 초기화
        }
    }

    private fun cleanupResources() {
        try {
            isInCall = false
            isNegotiating = false

            // Video/Audio 트랙 정리
            remoteVideoTrack?.removeSink(remoteVideoView)
            remoteVideoTrack = null
            remoteAudioTrack = null

            // PeerConnection 정리
            peerConnection?.dispose()
            peerConnection = null

            // UI 상태 업데이트
            updateUIState(CallState.IDLE)

            Log.d("WebRTC", "Resources cleaned up successfully")
        } catch (e: Exception) {
            Log.e("WebRTC", "Error cleaning up resources", e)
        }
    }

    private fun updateUIState(newState: CallState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateUIState(newState) }
            return
        }

        try {
            state = newState
            when (newState) {
                CallState.IDLE -> {
                    statusText.text = "대기 중"
                    callButton.visibility = View.VISIBLE
                    callButton.text = "통화"
                    endCallButton.visibility = View.GONE
                    userListSpinner.isEnabled = true
                    isInCall = false
                }
                CallState.CALLING -> {
                    statusText.text = "발신 중..."
                    callButton.visibility = View.GONE
                    endCallButton.visibility = View.VISIBLE
                    userListSpinner.isEnabled = false
                    isInCall = true
                }
                CallState.RECEIVING_CALL -> {
                    statusText.text = "수신 중..."
                    callButton.visibility = View.VISIBLE
                    callButton.text = "수락"
                    endCallButton.visibility = View.VISIBLE
                    userListSpinner.isEnabled = false
                    isInCall = false
                }
                CallState.CONNECTING -> {
                    statusText.text = "연결 중..."
                    callButton.visibility = View.GONE
                    endCallButton.visibility = View.VISIBLE
                    userListSpinner.isEnabled = false
                }
                CallState.IN_CALL -> {
                    statusText.text = "통화 중"
                    callButton.visibility = View.GONE
                    endCallButton.visibility = View.VISIBLE
                    userListSpinner.isEnabled = false
                    isInCall = true
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "Error updating UI state", e)
        }
    }



    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    enum class CallState {
        IDLE, CALLING, RECEIVING_CALL, CONNECTING, IN_CALL
    }

    override fun onDestroy() {
        super.onDestroy()
        socketHandler.disconnect()
        peerConnection?.dispose()
        videoCapturer?.dispose()
    }

    // MainActivity.kt 내부에 추가될 함수들

    private fun checkPermissions(): Boolean {
        return requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, requiredPermissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initialize()
            } else {
                showToast("카메라와 마이크 권한이 필요합니다")
                finish()
            }
        }
    }

    private fun initializeWebRTC() {
        try {
            // WebRTC 초기화
            val options = PeerConnectionFactory.InitializationOptions.builder(this)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)

            val encoderFactory = DefaultVideoEncoderFactory(
                eglBase.eglBaseContext,
                true,
                true
            )
            val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

            // PeerConnectionFactory 생성
//        val defaultConfig = PeerConnectionFactory.Options()
//        val encoderFactory = createCustomVideoEncoderFactory()
//        val decoderFactory = createCustomVideoDecoderFactory()

            peerConnectionFactory = PeerConnectionFactory.builder()
//            .setOptions(defaultConfig)
                .setVideoEncoderFactory(encoderFactory)
                .setVideoDecoderFactory(decoderFactory)
                .setOptions(PeerConnectionFactory.Options().apply {
                    disableNetworkMonitor = false
                    disableEncryption = false
                })
                .createPeerConnectionFactory()
            Log.d(
                "WebRTC",
                "PeerConnectionFactory initialized successfully: ${peerConnectionFactory != null}"
            )

            // 비디오 소스 설정
            initializeVideoSource()

            // 오디오 소스 설정
            initializeAudioSource()
        } catch (e: Exception) {
            Log.e("WebRTC", "Failed to initialize WebRTC", e)
            e.printStackTrace()
        }
    }

    private fun createCustomVideoEncoderFactory(): VideoEncoderFactory {

        return DefaultVideoEncoderFactory(
            eglBase.eglBaseContext,
            true,  // enableIntelVp8Encoder
            true   // enableH264HighProfile
        )
    }

    private fun createCustomVideoDecoderFactory(): VideoDecoderFactory {
        return DefaultVideoDecoderFactory(eglBase.eglBaseContext)
    }

    private fun initializeVideoSource() {
        val surfaceTextureHelper =
            SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)

        videoCapturer = createCameraCapturer()
        videoCapturer?.initialize(
            surfaceTextureHelper,
            applicationContext,
            videoSource.capturerObserver
        )
        videoCapturer?.startCapture(640, 480, 30)

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource).apply {
            setEnabled(true)  // VideoTrack 활성화
            addSink(localVideoView)
        }
    }

    private fun initializeAudioSource() {
        val audioConstraints = MediaConstraints()
        audioConstraints.mandatory.add(
            MediaConstraints.KeyValuePair("googEchoCancellation", "true")
        )
        audioConstraints.mandatory.add(
            MediaConstraints.KeyValuePair("googNoiseSuppression", "true")
        )
        audioConstraints.mandatory.add(
            MediaConstraints.KeyValuePair("googAutoGainControl", "true")
        )

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val cameraEnumerator = Camera2Enumerator(this)
        val deviceNames = cameraEnumerator.deviceNames

        // 전면 카메라 먼저 시도
        deviceNames.forEach { deviceName ->
            if (cameraEnumerator.isFrontFacing(deviceName)) {
                val capturer = cameraEnumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        // 후면 카메라 시도
        deviceNames.forEach { deviceName ->
            if (cameraEnumerator.isBackFacing(deviceName)) {
                val capturer = cameraEnumerator.createCapturer(deviceName, null)
                if (capturer != null) {
                    return capturer
                }
            }
        }

        return null
    }

    private fun createPeerConnection() {
        val rtcConfig = PeerConnection.RTCConfiguration(
            listOf(
                PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun.l.google.com:5349").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun1.l.google.com:5349").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun2.l.google.com:5349").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun3.l.google.com:3478").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun3.l.google.com:5349").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun4.l.google.com:19302").createIceServer(),
                PeerConnection.IceServer.builder("stun:stun4.l.google.com:5349").createIceServer(),
                PeerConnection.IceServer.builder("")
                    .setUsername("test")
                    .setPassword("")
                    .createIceServer()
            )
        ).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            iceTransportsType = PeerConnection.IceTransportsType.ALL
            enableDtlsSrtp = true
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }

        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(iceCandidate: IceCandidate) {
                Log.d("ICE", "Generated local ICE candidate:")
                Log.d("ICE", "SDP Mid: ${iceCandidate.sdpMid}")
                Log.d("ICE", "SDP MLineIndex: ${iceCandidate.sdpMLineIndex}")
                Log.d("ICE", "SDP: ${iceCandidate.sdp}")

                val candidateJson = JSONObject().apply {
                    put("sdpMid", iceCandidate.sdpMid)
                    put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                    put("candidate", iceCandidate.sdp)
                }
                targetUserId?.let { userId ->
                    Log.d("ICE", "Sending ICE candidate to: $userId")
                    socketHandler.sendIceCandidate(userId, candidateJson.toString())
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                Log.d("WebRTC", "onTrack called: ${transceiver.receiver.track()?.kind()}")
                val track = transceiver.receiver.track()
                when (track) {
                    is VideoTrack -> {
                        Log.d("WebRTC", "Video track received")
                        synchronized(this) {
                            remoteVideoTrack?.removeSink(remoteVideoView)
                            remoteVideoTrack = track
                        }
                        runOnUiThread {
                            try {
                                remoteVideoView.visibility = View.VISIBLE
                                remoteVideoView.setZOrderMediaOverlay(false)
                                remoteVideoView.setEnableHardwareScaler(true)

                                synchronized(this) {
                                    track.setEnabled(true)
                                    track.addSink(remoteVideoView)
                                }
                                Log.d("WebRTC", "Remote video sink added successfully")
                            } catch (e: Exception) {
                                Log.e("WebRTC", "Error adding remote video sink", e)
                                e.printStackTrace()
                            }
                        }
                    }
                    is AudioTrack -> {
                        Log.d("WebRTC", "Audio track received")
                        remoteAudioTrack = track
                        remoteAudioTrack?.setEnabled(true)
                    }
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                runOnUiThread {
                    when (newState) {

                        PeerConnection.PeerConnectionState.CONNECTED -> {
                            updateUIState(CallState.IN_CALL)
                        }

                        PeerConnection.PeerConnectionState.DISCONNECTED,
                        PeerConnection.PeerConnectionState.FAILED -> {
                            endCall()
                        }

                        else -> {}
                    }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {
                Log.d("WebRTC", "Signaling state changed to: $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.SignalingState.STABLE -> {
                            isNegotiating = false
                        }
                        PeerConnection.SignalingState.CLOSED -> {
                            runOnUiThread {
                                if (isInCall) {
                                    cleanupAndEndCall()
                                }
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                Log.d("WebRTC", "ICE Connection state changed to: $state")
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            Log.d("WebRTC", "ICE Checking in progress...")
                            updateUIState(CallState.CALLING)
                        }
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            Log.d("WebRTC", "ICE Connection Connected")
                            updateUIState(CallState.IN_CALL)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            Log.e("WebRTC", "ICE Connection Failed")
                            if (isInCall) {
                                Log.e("WebRTC", "ICE Connection Failed, attempting retry")
                                retryConnection()
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            if (isInCall) {
                                Log.w("WebRTC", "ICE Connection Disconnected, handling disconnection")
                                handleDisconnection()
                            }
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            runOnUiThread {
                                endCall()
                            }
                        }
                        else -> {}
                    }
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d("WebRTC", "ICE connection receiving changed to: $receiving")
                if (!receiving) {
                    // ICE candidate를 더 이상 받지 못하는 상황 처리
                    runOnUiThread {
                        showToast("네트워크 연결이 불안정합니다")
                    }
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                when (state) {
                    PeerConnection.IceGatheringState.GATHERING -> {
                        Log.d("WebRTC", "ICE gathering is in progress")
                    }

                    PeerConnection.IceGatheringState.COMPLETE -> {
                        Log.d("WebRTC", "ICE gathering completed")
                        // ICE candidate 수집이 완료되었을 때의 처리
                    }

                    else -> {
                        Log.d("WebRTC", "ICE gathering state changed to: $state")
                    }
                }
            }

            override fun onRemoveStream(stream: MediaStream?) {
                // 스트림이 제거되었을 때의 처리
                runOnUiThread {
                    remoteVideoView.release()
                }
            }

            override fun onDataChannel(dataChannel: DataChannel?) {
                // DataChannel이 생성되었을 때의 처리
                dataChannel?.registerObserver(object : DataChannel.Observer {
                    override fun onBufferedAmountChange(l: Long) {}

                    override fun onStateChange() {
                        Log.d("WebRTC", "DataChannel state changed: ${dataChannel.state()}")
                    }

                    override fun onMessage(buffer: DataChannel.Buffer) {
                        // 메시지 수신 처리
                    }
                })
            }

            override fun onRenegotiationNeeded() {
                Log.d("WebRTC", "onRenegotiationNeeded called, isInitiator: $isInitiator, isInCall: $isInCall")
                if (isInitiator) {
                    runOnUiThread {
                        synchronized(this@MainActivity) {
                            if (!isNegotiating) {
                                isNegotiating = true
                                createAndSendOffer()
                            } else {
                                Log.d("WebRTC", "Negotiation already in progress")
                            }
                        }
                    }
                }
            }

            override fun onAddTrack(
                rtpReceiver: RtpReceiver?,
                mediaStreams: Array<out MediaStream>?
            ) {
                // onTrack으로 대체되었으므로 여기서는 별도 처리하지 않음
                Log.d("WebRTC", "onAddTrack called (deprecated)")
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                // 제거된 ICE candidate 처리
                candidates?.forEach { candidate ->
                    Log.d("WebRTC", "ICE candidate removed: ${candidate.sdp}")
                    peerConnection?.removeIceCandidates(arrayOf(candidate))
                }
            }

            override fun onAddStream(stream: MediaStream?) {
                // Deprecated - onTrack을 사용하므로 여기서는 처리하지 않음
                Log.d("WebRTC", "onAddStream called (deprecated)")
            }

        }

        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, observer)
        isPeerConnectionCreated = true
        if (peerConnection == null) {
            Log.i("peerConnection", "isNull")
        }



//        val localStream = peerConnectionFactory.createLocalMediaStream("local-stream")
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV,listOf(myId))
        )

        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(myId))
        )

        localVideoTrack?.let { videoTrack ->
            peerConnection?.addTrack(videoTrack)
            Log.d("WebRTC", "Local video track added to PeerConnection")
        }

        localAudioTrack?.let { audioTrack ->
            peerConnection?.addTrack(audioTrack)
            Log.d("WebRTC", "Local audio track added to PeerConnection")
        }


    }
    private fun modifySdp(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n").toMutableList()
        var mediaSection = false
        var videoSection = false
        val modifiedLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("m=") -> {
                    mediaSection = true
                    videoSection = line.startsWith("m=video")
                    modifiedLines.add(line)
                    // 미디어 섹션 시작시 sendrecv 명시적 추가
                    modifiedLines.add("a=sendrecv")
                }
                // 기존의 미디어 방향 속성 제거
                line.startsWith("a=sendonly") ||
                        line.startsWith("a=recvonly") ||
                        line.startsWith("a=inactive") ||
                        line.startsWith("a=sendrecv") -> {
                    // 건너뛰기 (새로운 sendrecv로 대체)
                }
                else -> {
                    modifiedLines.add(line)
                }
            }

            // 비디오 섹션에 필요한 피드백 메커니즘 추가
            if (videoSection && line.startsWith("a=rtpmap")) {
                modifiedLines.add("a=rtcp-fb:* ccm fir")
                modifiedLines.add("a=rtcp-fb:* nack")
                modifiedLines.add("a=rtcp-fb:* nack pli")
                modifiedLines.add("a=rtcp-fb:* goog-remb")
            }
        }

        return modifiedLines.joinToString("\r\n")
    }


    private fun cleanupAndEndCall() {
        try {
            isInCall = false
            isNegotiating = false

            // 리소스 정리
            remoteVideoTrack?.removeSink(remoteVideoView)
            remoteVideoTrack = null
            remoteAudioTrack = null

            peerConnection?.dispose()
            peerConnection = null

            runOnUiThread {
                updateUIState(CallState.IDLE)
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "Error during cleanup", e)
        }
    }

    private fun retryConnection() {
        try {
            synchronized(this) {
                if (!isInCall) return

                // 기존 연결 정리
                peerConnection?.dispose()
                peerConnection = null
                isNegotiating = false

                // 새로운 연결 시도
                createPeerConnection()
                if (isInitiator) {
                    createAndSendOffer()
                }
            }
        } catch (e: Exception) {
            Log.e("WebRTC", "Error during connection retry", e)
            cleanupAndEndCall()
        }
    }

    private fun handleDisconnection() {
        try {
            showToast("연결이 일시적으로 끊겼습니다. 재연결을 시도합니다...")
            Handler(Looper.getMainLooper()).postDelayed({
                if (isInCall) {
                    retryConnection()
                }
            }, 3000)
        } catch (e: Exception) {
            Log.e("WebRTC", "Error during disconnection handling", e)
            cleanupAndEndCall()
        }
    }
}