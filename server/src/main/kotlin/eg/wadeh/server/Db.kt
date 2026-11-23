package eg.wadeh.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Timestamp
import java.sql.Types
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.sql.DataSource

class Db(val ds: DataSource) {
    fun <T> tx(block: Tx.() -> T): T = ds.connection.use { c ->
        c.autoCommit = false
        c.transactionIsolation = Connection.TRANSACTION_READ_COMMITTED
        try {
            val r = Tx(c).block(); c.commit(); r
        } catch (e: Throwable) { c.rollback(); throw e }
    }

    fun <T> read(block: Tx.() -> T): T = ds.connection.use { c -> c.isReadOnly = true; Tx(c).block() }

    companion object {
        fun connect(url: String): Db {
            val cfg = HikariConfig().apply {
                jdbcUrl = url; maximumPoolSize = 10; minimumIdle = 1
                connectionTimeout = 5_000; validationTimeout = 2_000
                addDataSourceProperty("ApplicationName", "wadeh-server")
            }
            return Db(HikariDataSource(cfg))
        }

        fun migrate(ds: DataSource): String? {
            val fw = Flyway.configure().dataSource(ds).locations("classpath:db/migration").load()
            fw.migrate()
            return fw.info().current()?.version?.version
        }
    }
}

class Tx(val c: Connection) {
    fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        args.forEachIndexed { i, a ->
            val idx = i + 1
            when (a) {
                null -> ps.setNull(idx, Types.NULL)
                is UUID -> ps.setObject(idx, a)
                is Instant -> ps.setTimestamp(idx, Timestamp.from(a))
                is LocalDate -> ps.setObject(idx, a)
                is Json -> ps.setObject(idx, a.text, Types.OTHER)
                is Array<*> -> ps.setArray(idx, c.createArrayOf("text", a))
                else -> ps.setObject(idx, a)
            }
        }
    }

    /** For statements that return a result we do not need (e.g. advisory locks). */
    fun run(sql: String, vararg args: Any?) { c.prepareStatement(sql).use { bind(it, args); it.execute() } }

    fun exec(sql: String, vararg args: Any?): Int = c.prepareStatement(sql).use { bind(it, args); it.executeUpdate() }

    fun <T> list(sql: String, vararg args: Any?, map: (ResultSet) -> T): List<T> =
        c.prepareStatement(sql).use { ps -> bind(ps, args); ps.executeQuery().use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    fun <T> one(sql: String, vararg args: Any?, map: (ResultSet) -> T): T? = list(sql, *args, map = map).firstOrNull()
}

/** Wrapper so jsonb parameters are bound as OTHER. */
@JvmInline value class Json(val text: String)

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)
fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)
fun ResultSet.instant(col: String): Instant = getTimestamp(col).toInstant()
fun ResultSet.instantOrNull(col: String): Instant? = getTimestamp(col)?.toInstant()
fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }
fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }
fun ResultSet.doubleOrNull(col: String): Double? = getDouble(col).let { if (wasNull()) null else it }

fun newId(): UUID = UUID.randomUUID()
