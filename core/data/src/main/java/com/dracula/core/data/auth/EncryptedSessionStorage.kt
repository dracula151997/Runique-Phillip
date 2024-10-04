package com.dracula.core.data.auth

import android.content.SharedPreferences
import androidx.core.content.edit
import com.dracula.core.domain.AuthInfo
import com.dracula.core.domain.SessionStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class EncryptedSessionStorage(
	private val sharedPreferences: SharedPreferences,
) : SessionStorage {

	override suspend fun get(): AuthInfo? {
		return withContext(Dispatchers.IO) {
			val json = sharedPreferences.getString(KEY_AUTH_INFO, null)
			json?.let {
				Json.decodeFromString<AuthInfoSerializable>(it).toAuthInfo()
			}

		}
	}

	override suspend fun set(info: AuthInfo?) {
		withContext(Dispatchers.IO) {
			info?.let { authInfo ->
				val authInfoAsString = Json.encodeToString(authInfo.toAuthInfoSerializable())
				sharedPreferences.edit {
					putString(KEY_AUTH_INFO, authInfoAsString)
				}
			} ?: sharedPreferences.edit {
				remove(KEY_AUTH_INFO)
			}
		}
	}

	companion object {
		private const val KEY_AUTH_INFO = "auth_info"
	}

}