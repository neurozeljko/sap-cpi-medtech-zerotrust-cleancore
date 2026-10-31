import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import groovy.json.JsonOutput
import java.security.MessageDigest

def Message processData(Message message) {
    def body = message.getBody(String)
    def jsonSlurper = new JsonSlurper()
    def payload = jsonSlurper.parseText(body)

    String txnId = payload.TransactionID ?: payload.DeliveryDocument ?: UUID.randomUUID().toString()
    MessageDigest digest = MessageDigest.getInstance("SHA-256")
    byte[] hashBytes = digest.digest(txnId.getBytes("UTF-8"))
    String sha256Hex = hashBytes.collect { String.format("%02x", it) }.join('')

    message.setHeader("TransactionHash", sha256Hex)
    message.setHeader("X-Correlation-ID", sha256Hex)

    if (payload.TrackingNumber) {
        String rawTracking = payload.TrackingNumber.toString()
        payload.TrackingNumber = rawTracking.length() > 4 ? "***-**-" + rawTracking.substring(rawTracking.length() - 4) : "***"
    }
    if (payload.UnitPrice) {
        payload.UnitPrice = "***CONFIDENTIAL***"
    }
    if (payload.RecipientName) {
        payload.RecipientName = "***REDACTED***"
    }

    try {
        def messageLog = messageLogFactory?.getMessageLog(message)
        if (messageLog) {
            messageLog.addCustomHeaderProperty("CorrelationID", sha256Hex)
            messageLog.addCustomHeaderProperty("MovementType", payload.MovementType ?: "Unknown")
            messageLog.setStringProperty("ZeroTrustStatus", "Scrubbed_And_Hashed")
        }
    } catch (Exception e) {}

    message.setBody(JsonOutput.toJson(payload))
    return message
}
