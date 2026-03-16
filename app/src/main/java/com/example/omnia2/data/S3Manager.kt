package com.example.omnia2.data

import android.content.Context
import android.util.Log
import com.amazonaws.ClientConfiguration
import com.amazonaws.HttpMethod
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.S3ClientOptions
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.S3ObjectSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.*

class S3Manager(context: Context) {
    private val credentials = BasicAWSCredentials(S3Config.ACCESS_KEY, S3Config.SECRET_KEY)
    
    private val s3Client: AmazonS3Client by lazy {
        disableSSLCertificateChecking()
        // Utilisation du constructeur non-déprécié avec la région
        val region = Region.getRegion(Regions.fromName(S3Config.REGION))
        AmazonS3Client(credentials, region, ClientConfiguration()).apply {
            setEndpoint(S3Config.ENDPOINT)
            setS3ClientOptions(S3ClientOptions.builder().setPathStyleAccess(true).build())
        }
    }

    private fun disableSSLCertificateChecking() {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate>? = null
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })
            val sc = SSLContext.getInstance("TLS")
            sc.init(null, trustAllCerts, SecureRandom())
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
            HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        } catch (e: Exception) {
            Log.e("S3Manager", "SSL bypass error", e)
        }
    }

    suspend fun uploadFile(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            val request = PutObjectRequest(S3Config.BUCKET, file.name, file)
            s3Client.putObject(request)
            Log.d("S3Manager", "Upload successful: ${file.name}")
            true
        } catch (e: Exception) {
            Log.e("S3Manager", "Upload failed", e)
            false
        }
    }

    suspend fun listFiles(): List<S3ObjectSummary> = withContext(Dispatchers.IO) {
        try {
            val result = s3Client.listObjects(S3Config.BUCKET)
            result.objectSummaries.sortedByDescending { it.lastModified }
        } catch (e: Exception) {
            Log.e("S3Manager", "List failed", e)
            emptyList()
        }
    }

    fun getFileUrl(key: String): String? {
        return try {
            val expiration = Date(System.currentTimeMillis() + 3600 * 1000) // 1 heure
            val generatePresignedUrlRequest = GeneratePresignedUrlRequest(S3Config.BUCKET, key)
                .withMethod(HttpMethod.GET)
                .withExpiration(expiration)
            s3Client.generatePresignedUrl(generatePresignedUrlRequest).toString()
        } catch (e: Exception) {
            Log.e("S3Manager", "URL generation failed", e)
            null
        }
    }
}
