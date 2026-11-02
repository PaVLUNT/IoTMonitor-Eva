package cl.pallendes.iotmonitor

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Reading(val ts: Long, val source: String, val temp: Double, val hum: Double, val fan: Boolean)

/** Almacenamiento local SQLite: usuarios (login sin conexión) y lecturas de sensores. Consultas parametrizadas. */
class Database(ctx: Context) : SQLiteOpenHelper(ctx, "iot.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT, username TEXT UNIQUE NOT NULL, " +
                "salt TEXT NOT NULL, hash TEXT NOT NULL, created INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE readings(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, " +
                "source TEXT NOT NULL, temp REAL NOT NULL, hum REAL NOT NULL, fan INTEGER NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}

    /** @return false si el usuario ya existe. */
    fun createUser(username: String, password: CharArray): Boolean {
        val salt = PasswordHasher.newSalt()
        val cv = ContentValues().apply {
            put("username", username)
            put("salt", PasswordHasher.encode(salt))
            put("hash", PasswordHasher.encode(PasswordHasher.hash(password, salt)))
            put("created", System.currentTimeMillis())
        }
        return writableDatabase.insert("users", null, cv) != -1L
    }

    fun verifyUser(username: String, password: CharArray): Boolean {
        readableDatabase.rawQuery("SELECT salt, hash FROM users WHERE username = ?", arrayOf(username)).use { c ->
            if (!c.moveToFirst()) {
                // Hash ficticio para igualar tiempos de respuesta (evita enumerar usuarios)
                PasswordHasher.hash(password, ByteArray(16)); return false
            }
            return PasswordHasher.matches(password, c.getString(0), c.getString(1))
        }
    }

    fun insertReading(r: Reading) {
        val cv = ContentValues().apply {
            put("ts", r.ts); put("source", r.source); put("temp", r.temp); put("hum", r.hum)
            put("fan", if (r.fan) 1 else 0)
        }
        writableDatabase.insert("readings", null, cv)
    }

    fun lastReadings(n: Int): List<Reading> {
        val out = mutableListOf<Reading>()
        readableDatabase.rawQuery(
            "SELECT ts, source, temp, hum, fan FROM readings ORDER BY id DESC LIMIT ?", arrayOf(n.toString())
        ).use { c ->
            while (c.moveToNext()) out += Reading(c.getLong(0), c.getString(1), c.getDouble(2), c.getDouble(3), c.getInt(4) == 1)
        }
        return out
    }

    fun avgTemp(): Double? =
        readableDatabase.rawQuery("SELECT AVG(temp) FROM readings", null).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getDouble(0) else null
        }
}
