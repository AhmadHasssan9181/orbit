package webrtc
import dev.onvoid.webrtc.RTCPeerConnection
import dev.onvoid.webrtc.RTCStatsCollectorCallback
import dev.onvoid.webrtc.RTCStatsReport
fun testStats(pc: RTCPeerConnection) {
    pc.getStats(object : RTCStatsCollectorCallback {
        override fun onStatsDelivered(report: RTCStatsReport) {
            println(report.stats)
        }
    })
}
