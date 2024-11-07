package com.example.claude

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.*
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
    private lateinit var userListSpinner: Spinner

    private var isEndingCall = AtomicBoolean(false)
    private var localVideoTrack: VideoTrack? = null
    private var localAudioTrack: AudioTrack? = null
    private var remoteVideoTrack: VideoTrack? = null
    private var remoteAudioTrack: AudioTrack? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: CameraVideoCapturer? = null

    private var targetUserId: String? = null
    private var myId: String? = null
    private var isInCall = false
    private var isNegotiating = false
    private var state = CallState.IDLE
    private var isInitiator = false
    private var isPeerConnectionCreated = false

    private val socketHandler = SocketHandler.getInstance()
    private val userList = mutableListOf<String>()
    private val spinnerAdapter by lazy {
        ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, userList)
    }

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        private val TAG = MainActivity::class.java.simpleName
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeBase()
        if (checkPermissions()) {
            initialize()
        } else {
            requestPermissions()
        }
    }

    private fun initializeBase() {
        eglBase = EglBase.create()
        myId = getDeviceUuid()
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceUuid(): String? {
        return Settings.Secure.getString(
            applicationContext.contentResolver,
            Settings.Secure.ANDROID_ID
        )
    }

    private fun initialize() {
        setupViews()
        initializeWebRTC()
        connectToSignalingServer()
    }

    private fun setupViews() {
        initializeVideoViews()
        initializeButtons()
        initializeSpinner()
        updateUIState(CallState.IDLE)
    }

    private fun initializeVideoViews() {
        localVideoView = findViewById(R.id.localVideoView)
        remoteVideoView = findViewById(R.id.remoteVideoView)
        statusText = findViewById(R.id.statusText)

        localVideoView.apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(true)
        }

        remoteVideoView.apply {
            init(eglBase.eglBaseContext, null)
            setEnableHardwareScaler(true)
            setMirror(false)
        }
    }

    private fun initializeButtons() {
        callButton = findViewById(R.id.callButton)
        endCallButton = findViewById(R.id.endCallButton)

        callButton.setOnClickListener {
            handleCallButtonClick()
        }

        endCallButton.setOnClickListener {
            endCall()
        }
    }

    private fun initializeSpinner() {
        userListSpinner = findViewById(R.id.userListSpinner)
        userListSpinner.adapter = spinnerAdapter
        userListSpinner.onItemSelectedListener = createSpinnerListener()
    }

    private fun createSpinnerListener(): AdapterView.OnItemSelectedListener {
        return object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                targetUserId = userList[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {
                targetUserId = null
            }
        }
    }

    private fun handleCallButtonClick() {
        targetUserId?.let { userId ->
            when {
                !isInCall && state != CallState.RECEIVING_CALL -> startCall(userId)
                state == CallState.RECEIVING_CALL -> acceptCall(userId)
            }
        } ?: showToast("통화할 사용자를 선택해주세요")
    }

    private fun initializeWebRTC() {
        try {
            initializePeerConnectionFactory()
            initializeVideoSource()
            initializeAudioSource()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WebRTC", e)
        }
    }

    private fun initializePeerConnectionFactory() {
        val options = PeerConnectionFactory.InitializationOptions.builder(this)
            .setEnableInternalTracer(true)
            .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
            .createInitializationOptions()

        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .setOptions(PeerConnectionFactory.Options().apply {
                disableNetworkMonitor = false
                disableEncryption = false
            })
            .createPeerConnectionFactory()
    }

    private fun initializeVideoSource() {
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase.eglBaseContext)
        val videoSource = peerConnectionFactory.createVideoSource(false)

        videoCapturer = createCameraCapturer()
        videoCapturer?.apply {
            initialize(surfaceTextureHelper, applicationContext, videoSource.capturerObserver)
            startCapture(640, 480, 30)
        }

        localVideoTrack = peerConnectionFactory.createVideoTrack("video_track", videoSource).apply {
            setEnabled(true)
            addSink(localVideoView)
        }
    }

    private fun initializeAudioSource() {
        val audioConstraints = MediaConstraints().apply {
            mandatory.addAll(listOf(
                MediaConstraints.KeyValuePair("googEchoCancellation", "true"),
                MediaConstraints.KeyValuePair("googNoiseSuppression", "true"),
                MediaConstraints.KeyValuePair("googAutoGainControl", "true")
            ))
        }

        val audioSource = peerConnectionFactory.createAudioSource(audioConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack("audio_track", audioSource)
    }

    private fun createCameraCapturer(): CameraVideoCapturer? {
        val cameraEnumerator = Camera2Enumerator(this)
        val deviceNames = cameraEnumerator.deviceNames

        // Try front camera first
        deviceNames.firstOrNull { cameraEnumerator.isFrontFacing(it) }?.let {
            return cameraEnumerator.createCapturer(it, null)
        }

        // Try back camera if front camera failed
        deviceNames.firstOrNull { cameraEnumerator.isBackFacing(it) }?.let {
            return cameraEnumerator.createCapturer(it, null)
        }

        return null
    }

    private fun connectToSignalingServer() {
        val userId = UUID.randomUUID().toString()
        socketHandler.init("http://10.0.2.2:3000")
        socketHandler.connect(userId) { success ->
            runOnUiThread {
                statusText.text = if (success) "서버에 연결됨" else "서버 연결 실패"
                if (success) registerCallEvents()
            }
        }
    }

    private fun registerCallEvents() {
        socketHandler.registerCallEvents(
            onCallReceived = { callerId ->
                Log.d(TAG, "Call received from: $callerId")
                runOnUiThread {
                    targetUserId = callerId
                    updateUIState(CallState.RECEIVING_CALL)
                }
            },
            onCallAccepted = { accepterId ->
                Log.d(TAG, "Call accepted by: $accepterId")
                runOnUiThread {
                    if (isInitiator) {
                        isNegotiating = false
                        createPeerConnection()
                        createAndSendOffer()
                        updateUIState(CallState.IN_CALL)
                    }
                }
            },
            onCallRejected = {
                runOnUiThread {
                    updateUIState(CallState.IDLE)
                    showToast("통화가 거절되었습니다")
                }
            },
            onCallEnded = {
                runOnUiThread { endCall() }
            },
            onOffer = { callerId, offerSdp ->
                Log.d(TAG, "Received Offer SDP from $callerId")
                runOnUiThread { handleOffer(callerId, offerSdp) }
            },
            onAnswer = { _, answerSdp ->
                Log.d(TAG, "Received Answer SDP")
                runOnUiThread { handleAnswer(answerSdp) }
            },
            onIceCandidate = { _, candidateJson ->
                Log.d(TAG, "Received ICE candidate")
                runOnUiThread { handleIceCandidate(candidateJson) }
            }
        )

        setupSocketListeners()
    }

    private fun handleOffer(callerId: String, offerSdp: String) {
        Log.d(TAG, "Received Offer SDP from $callerId")

        if (!isPeerConnectionCreated) {
            createPeerConnection()
        }

        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null when handling offer")
            createPeerConnection()
        }

        if (isNegotiating || peerConnection?.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
            Log.d(TAG, "Ignoring offer - already negotiating or have local offer")
            return
        }

        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        isNegotiating = true

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                createAndSendAnswer()
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
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
        val sessionDescription = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)

        peerConnection?.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                Log.d(TAG, "Remote description set successfully")
                isNegotiating = false
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set remote description: $error")
                isNegotiating = false
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
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
            Log.d(TAG, "Added ICE candidate successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate", e)
        }
    }

    private fun createAndSendAnswer() {
        Log.d(TAG, "Creating answer")
        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                val modifiedSdp = modifySdp(sessionDescription.description)
                val finalDescription = SessionDescription(sessionDescription.type, modifiedSdp)

                peerConnection?.setLocalDescription(object : SdpObserver {
                    override fun onSetSuccess() {
                        targetUserId?.let { userId ->
                            socketHandler.sendAnswer(userId, finalDescription.description)
                        }
                        isNegotiating = false
                    }

                    override fun onSetFailure(error: String?) {
                        Log.e(TAG, "Failed to set local description: $error")
                        isNegotiating = false
                    }

                    override fun onCreateSuccess(p0: SessionDescription?) {}
                    override fun onCreateFailure(p0: String?) {}
                }, finalDescription)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Failed to create answer: $error")
                isNegotiating = false
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(p0: String?) {}
        }, mediaConstraints)
    }

    private fun setupSocketListeners() {
        socketHandler.socket?.apply {
            on("userList") { args ->
                val users = (args[0] as JSONArray).let { array ->
                    List(array.length()) { i -> array.getString(i) }
                }
                runOnUiThread {
                    userList.clear()
                    userList.addAll(users.filter { it != socketHandler.getUserId() })
                    spinnerAdapter.notifyDataSetChanged()
                }
            }

            on("user-disconnected") { args ->
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
    }

    private fun createPeerConnection() {
        val rtcConfig = createRtcConfiguration()
        peerConnection = peerConnectionFactory.createPeerConnection(rtcConfig, createPeerConnectionObserver())
        isPeerConnectionCreated = true

        setupLocalTracks()
    }

    private fun createRtcConfiguration(): PeerConnection.RTCConfiguration {
        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("turn:222.112.209.235:3478")
                .setUsername("test")
                .setPassword("test123")
                .createIceServer()
        )

        return PeerConnection.RTCConfiguration(iceServers).apply {
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.ENABLED
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            keyType = PeerConnection.KeyType.ECDSA
            enableDtlsSrtp = true
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        }
    }

    private fun setupLocalTracks() {
        peerConnection?.apply {
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(myId))
            )
            addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV, listOf(myId))
            )

            localVideoTrack?.let { addTrack(it) }
            localAudioTrack?.let { addTrack(it) }
        }
    }

    private fun createPeerConnectionObserver(): PeerConnection.Observer {
        return object : PeerConnection.Observer {
            override fun onIceCandidate(iceCandidate: IceCandidate) {
                val candidateJson = JSONObject().apply {
                    put("sdpMid", iceCandidate.sdpMid)
                    put("sdpMLineIndex", iceCandidate.sdpMLineIndex)
                    put("candidate", iceCandidate.sdp)
                }
                targetUserId?.let { userId ->
                    socketHandler.sendIceCandidate(userId, candidateJson.toString())
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                when (track) {
                    is VideoTrack -> handleVideoTrack(track)
                    is AudioTrack -> handleAudioTrack(track)
                }
            }

            private fun handleVideoTrack(track: VideoTrack) {
                synchronized(this@MainActivity) {
                    remoteVideoTrack?.removeSink(remoteVideoView)
                    remoteVideoTrack = track
                }

                runOnUiThread {
                    remoteVideoView.visibility = View.VISIBLE
                    remoteVideoView.setZOrderMediaOverlay(false)
                    remoteVideoView.setEnableHardwareScaler(true)

                    synchronized(this@MainActivity) {
                        track.setEnabled(true)
                        track.addSink(remoteVideoView)
                    }
                }
            }

            private fun handleAudioTrack(track: AudioTrack) {
                remoteAudioTrack = track
                remoteAudioTrack?.setEnabled(true)
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
                when (state) {
                    PeerConnection.SignalingState.STABLE -> {
                        isNegotiating = false
                    }
                    PeerConnection.SignalingState.CLOSED -> {
                        if (isInCall) {
                            cleanupAndEndCall()
                        }
                    }
                    else -> {}
                }
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                runOnUiThread {
                    when (state) {
                        PeerConnection.IceConnectionState.CHECKING -> {
                            updateUIState(CallState.CALLING)
                        }
                        PeerConnection.IceConnectionState.CONNECTED -> {
                            updateUIState(CallState.IN_CALL)
                        }
                        PeerConnection.IceConnectionState.FAILED -> {
                            if (isInCall) {
                                retryConnection()
                            }
                        }
                        PeerConnection.IceConnectionState.DISCONNECTED -> {
                            if (isInCall) {
                                handleDisconnection()
                            }
                        }
                        PeerConnection.IceConnectionState.CLOSED -> {
                            endCall()
                        }
                        else -> {}
                    }
                }
            }

            override fun onRenegotiationNeeded() {
                if (isInitiator) {
                    runOnUiThread {
                        synchronized(this@MainActivity) {
                            if (!isNegotiating) {
                                isNegotiating = true
                                createAndSendOffer()
                            }
                        }
                    }
                }
            }

            // Required override methods with minimal implementation
            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                if (!receiving) {
                    runOnUiThread { showToast("네트워크 연결이 불안정합니다") }
                }
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(dataChannel: DataChannel?) {}
            override fun onAddTrack(rtpReceiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        }
    }

    private fun createAndSendOffer() {
        if (peerConnection == null) {
            Log.e(TAG, "PeerConnection is null when trying to create offer")
            return
        }

        val mediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription) {
                val modifiedSdp = SessionDescription(
                    sessionDescription.type,
                    modifySdp(sessionDescription.description)
                )

                setLocalDescriptionAndSendOffer(modifiedSdp)
            }

            override fun onCreateFailure(error: String?) {
                Log.e(TAG, "Offer creation failed: $error")
                isNegotiating = false
            }

            override fun onSetSuccess() {}
            override fun onSetFailure(error: String?) {}
        }, mediaConstraints)
    }

    private fun setLocalDescriptionAndSendOffer(sessionDescription: SessionDescription) {
        peerConnection?.setLocalDescription(object : SdpObserver {
            override fun onSetSuccess() {
                targetUserId?.let { userId ->
                    socketHandler.sendOffer(userId, sessionDescription.description)
                }
            }

            override fun onSetFailure(error: String?) {
                Log.e(TAG, "Failed to set local description: $error")
                isNegotiating = false
            }

            override fun onCreateSuccess(p0: SessionDescription?) {}
            override fun onCreateFailure(p0: String?) {}
        }, sessionDescription)
    }

    private fun modifySdp(sdpDescription: String): String {
        val lines = sdpDescription.split("\r\n").toMutableList()
        var videoSection = false
        val modifiedLines = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("m=") -> {
                    videoSection = line.startsWith("m=video")
                    modifiedLines.add(line)
                    modifiedLines.add("a=sendrecv")
                }
                line.startsWith("a=sendonly") ||
                        line.startsWith("a=recvonly") ||
                        line.startsWith("a=inactive") ||
                        line.startsWith("a=sendrecv") -> {
                    // Skip existing media direction attributes
                    continue
                }
                else -> {
                    modifiedLines.add(line)
                }
            }

            if (videoSection && line.startsWith("a=rtpmap")) {
                modifiedLines.addAll(listOf(
                    "a=rtcp-fb:* ccm fir",
                    "a=rtcp-fb:* nack",
                    "a=rtcp-fb:* nack pli",
                    "a=rtcp-fb:* goog-remb"
                ))
            }
        }

        return modifiedLines.joinToString("\r\n")
    }

    private fun cleanupResources() {
        isInCall = false
        isNegotiating = false

        remoteVideoTrack?.removeSink(remoteVideoView)
        remoteVideoTrack = null
        remoteAudioTrack = null

        peerConnection?.dispose()
        peerConnection = null

        updateUIState(CallState.IDLE)
    }

    private fun startCall(targetUserId: String) {
        isInitiator = true
        createAndInitializePeerConnection()

        Handler(Looper.getMainLooper()).postDelayed({
            if (peerConnection != null && isPeerConnectionCreated) {
                socketHandler.sendCallRequest(targetUserId)
                updateUIState(CallState.CALLING)
            } else {
                Log.e(TAG, "Failed to initialize peer connection")
                showToast("연결 설정에 실패했습니다")
                cleanupAndEndCall()
            }
        }, 1000) // 1초 지연으로 초기화 완료 보장
    }

    private fun acceptCall(callerId: String) {
        isInitiator = false
        isNegotiating = false
        createAndInitializePeerConnection()

        Handler(Looper.getMainLooper()).postDelayed({
            if (peerConnection != null && isPeerConnectionCreated) {
                socketHandler.sendCallAccept(callerId)
                updateUIState(CallState.IN_CALL)
            } else {
                Log.e(TAG, "Failed to initialize peer connection")
                showToast("연결 설정에 실패했습니다")
                endCall()
            }
        }, 1000)
    }

    private fun createAndInitializePeerConnection() {
        if (!isPeerConnectionFactoryInitialized()) {
            Log.e(TAG, "PeerConnectionFactory is not initialized")
            initializeWebRTC()
        }

        if (peerConnection == null) {
            createPeerConnection()
        }

        if (peerConnection == null) {
            Log.e(TAG, "Failed to create PeerConnection")
            return
        }
    }

    private fun isPeerConnectionFactoryInitialized(): Boolean {
        return this::peerConnectionFactory.isInitialized &&
                localAudioTrack != null &&
                localVideoTrack != null
    }

    private fun cleanupAndEndCall() {
        isInCall = false
        isNegotiating = false

        remoteVideoTrack?.removeSink(remoteVideoView)
        remoteVideoTrack = null
        remoteAudioTrack = null

        peerConnection?.dispose()
        peerConnection = null

        updateUIState(CallState.IDLE)
    }

    private fun updateUIState(newState: CallState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { updateUIState(newState) }
            return
        }

        state = newState
        when (newState) {
            CallState.IDLE -> {
                statusText.text = "대기 중"
                callButton.apply {
                    visibility = View.VISIBLE
                    text = "통화"
                }
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
                callButton.apply {
                    visibility = View.VISIBLE
                    text = "수락"
                }
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
    }

    private fun endCall() {
        if (!isEndingCall.compareAndSet(false, true)) {
            return
        }

        try {
            if (isInCall) {
                targetUserId?.let { userId ->
                    socketHandler.sendEndCall(userId)
                }
                runOnUiThread { cleanupResources() }
            }
        } finally {
            isEndingCall.set(false)
        }
    }

    private fun retryConnection() {
        synchronized(this) {
            if (!isInCall) return

            peerConnection?.dispose()
            peerConnection = null
            isNegotiating = false

            createPeerConnection()
            if (isInitiator) {
                createAndSendOffer()
            }
        }
    }

    private fun handleDisconnection() {
        showToast("연결이 일시적으로 끊겼습니다. 재연결을 시도합니다...")
        Handler(Looper.getMainLooper()).postDelayed({
            if (isInCall) {
                retryConnection()
            }
        }, 3000)
    }

    private fun checkPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        socketHandler.disconnect()
        peerConnection?.dispose()
        videoCapturer?.dispose()
    }

    enum class CallState {
        IDLE, CALLING, RECEIVING_CALL, CONNECTING, IN_CALL
    }
}