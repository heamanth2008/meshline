package com.meshline.core

interface Transport {
    var listener: TransportListener?
    fun start()
    fun stop()
    fun isConnected(nodeId: String): Boolean
    fun send(nodeId: String, bytes: ByteArray)
    fun broadcast(bytes: ByteArray, exceptNode: String? = null)
    fun getConnectedPeers(): List<String>
}

interface TransportListener {
    fun onPeerConnected(nodeId: String)
    fun onPeerLost(nodeId: String)
    fun onBytes(nodeId: String, bytes: ByteArray)
}
