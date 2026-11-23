package eg.wadeh.server

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object Passwords {
    private const val ITER = 210_000
    private val rnd = SecureRandom()

    fun hash(pw: String): String {
        val salt = ByteArray(16).also(rnd::nextBytes)
        return "pbkdf2_sha256$$ITER$${b64(salt)}$${b64(derive(pw, salt, ITER))}"
    }

    fun verify(pw: String, stored: String): Boolean {
        val p = stored.split('$')
        if (p.size != 4 || p[0] != "pbkdf2_sha256") return false
        val calc = derive(pw, Base64.getDecoder().decode(p[2]), p[1].toInt())
        return MessageDigest.isEqual(calc, Base64.getDecoder().decode(p[3]))
    }

    private fun derive(pw: String, salt: ByteArray, iter: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pw.toCharArray(), salt, iter, 256)).encoded

    private fun b64(b: ByteArray) = Base64.getEncoder().withoutPadding().encodeToString(b)
}

fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
fun sha256Hex(s: String) = sha256Hex(s.toByteArray())

data class Membership(val orgId: UUID, val role: String)

/** Who is acting. Permissions are computed server-side from these facts only. */
data class Principal(
    val userId: UUID,
    val email: String,
    val displayName: String,
    val platformRole: String?,
    val memberships: List<Membership>,
) {
    val isPlatformAdmin get() = platformRole == "platform_admin"
    fun canUploadFor(org: UUID) = isPlatformAdmin || memberships.any { it.orgId == org && it.role in setOf("org_owner", "supplier_agent") }
    fun canReviewFor(org: UUID) = isPlatformAdmin || platformRole == "verifier" || memberships.any { it.orgId == org && it.role == "verifier" }
    fun canSee(org: UUID) = isPlatformAdmin || platformRole in setOf("verifier", "support", "analyst") || memberships.any { it.orgId == org }
}

class Sessions(private val db: Db) {
    private val rnd = SecureRandom()
    private val ttl = Duration.ofHours(12)

    fun login(email: String, password: String): String? = db.tx {
        val row = one("SELECT id, password_hash, disabled FROM app_user WHERE lower(email)=lower(?)", email) {
            Triple(it.uuid("id"), it.getString("password_hash"), it.getBoolean("disabled"))
        } ?: return@tx null
        if (row.third || !Passwords.verify(password, row.second)) return@tx null
        val token = ByteArray(32).also(rnd::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        exec("INSERT INTO session_token(token_hash,user_id,expires_at) VALUES (?,?,?)", sha256Hex(token), row.first, Instant.now().plus(ttl))
        exec("INSERT INTO audit_log(actor_user_id, action, entity, entity_id) VALUES (?,?,?,?)", row.first, "login", "app_user", row.first.toString())
        token
    }

    fun logout(token: String) = db.tx { exec("DELETE FROM session_token WHERE token_hash=?", sha256Hex(token)) }

    fun principal(token: String?): Principal? {
        if (token.isNullOrBlank()) return null
        return db.read {
            val u = one(
                """SELECT u.id,u.email,u.display_name,u.platform_role FROM session_token s JOIN app_user u ON u.id=s.user_id
                   WHERE s.token_hash=? AND s.expires_at>now() AND NOT u.disabled""", sha256Hex(token)
            ) { Principal(it.uuid("id"), it.getString("email"), it.getString("display_name"), it.getString("platform_role"), emptyList()) } ?: return@read null
            u.copy(memberships = list("SELECT org_id, role FROM membership WHERE user_id=?", u.userId) { Membership(it.uuid("org_id"), it.getString("role")) })
        }
    }
}
