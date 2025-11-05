package com.example.myapplication.data.network

import android.content.Context
import com.example.myapplication.BuildConfig
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {
    private fun baseUrl(): String {
        val raw = BuildConfig.BASE_URL
        return if (raw.endsWith('/')) raw else "$raw/"
    }

    private fun createOkHttpClient(context: Context): OkHttpClient {
        val tokenStore = SharedPrefsTokenStore(context)
        // Configure HTTP logging: enabled at BODY level only when BuildConfig.DEBUG is true
        val logging = HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BODY else HttpLoggingInterceptor.Level.NONE
        }

        // Avoid printing sensitive headers even in debug logs
        if (BuildConfig.DEBUG) {
            try {
                logging.redactHeader("Authorization")
                logging.redactHeader("Cookie")
            } catch (_: Exception) {  }
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(TokenInterceptor(tokenStore))

        builder.addInterceptor(logging)

        return builder.build()
    }

    fun createRetrofit(context: Context): Retrofit {
        // Create a lenient Gson instance that handles null values and type mismatches gracefully
        val gsonBuilder = GsonBuilder()
            .setLenient() // Accept malformed JSON

        // A helper deserializer for Int that tolerates string/empty/null values
        val intDeserializer = com.google.gson.JsonDeserializer<Int?> { json: JsonElement?, _, _ ->
            try {
                if (json == null || json.isJsonNull) return@JsonDeserializer null
                val prim = json.asJsonPrimitive
                return@JsonDeserializer when {
                    prim.isNumber -> prim.asInt
                    prim.isString -> prim.asString.takeIf { it.isNotBlank() }?.toIntOrNull()
                    else -> null
                }
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("GsonIntDeserializer", "Failed to parse Int from: $json", ex)
                null
            }
        }

        // A helper deserializer for Boolean that tolerates string/empty/null values
        val boolDeserializer = com.google.gson.JsonDeserializer<Boolean?> { json: JsonElement?, _, _ ->
            try {
                if (json == null || json.isJsonNull) return@JsonDeserializer null
                val prim = json.asJsonPrimitive
                return@JsonDeserializer when {
                    prim.isBoolean -> prim.asBoolean
                    prim.isNumber -> prim.asInt != 0
                    prim.isString -> {
                        when (val s = prim.asString.trim().lowercase()) {
                            "true", "1", "yes" -> true
                            "false", "0", "no", "" -> false
                            else -> null
                        }
                    }
                    else -> null
                }
            } catch (ex: Exception) {
                if (BuildConfig.DEBUG) android.util.Log.w("GsonBoolDeserializer", "Failed to parse Boolean from: $json", ex)
                null
            }
        }

        // Register for both boxed and primitive variants to be safe
        gsonBuilder.registerTypeAdapter(Int::class.javaObjectType, intDeserializer)
        Int::class.javaPrimitiveType?.let { gsonBuilder.registerTypeAdapter(it, intDeserializer) }
        gsonBuilder.registerTypeAdapter(Boolean::class.javaObjectType, boolDeserializer)
        Boolean::class.javaPrimitiveType?.let { gsonBuilder.registerTypeAdapter(it, boolDeserializer) }

        val gson = gsonBuilder.create()

        return Retrofit.Builder()
            .baseUrl(baseUrl())
            .client(createOkHttpClient(context))
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
    }

    fun createApiService(context: Context): ApiService =
        createRetrofit(context).create(ApiService::class.java)
}
