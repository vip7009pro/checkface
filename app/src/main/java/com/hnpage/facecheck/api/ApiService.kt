package com.hnpage.facecheck.api

import com.google.gson.JsonObject
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST

interface ApiService {
  @POST("api")
  suspend fun login2(@Body loginRequest: JsonObject): Response<JsonObject>

  @POST("api")
  suspend fun fetchData(@Body data: JsonObject): Response<JsonObject>
}

class ApiHandler(private val globalVar : GlobalVariable) {

  val retrofit = Retrofit.Builder().baseUrl(globalVar.getServer())
    .addConverterFactory(GsonConverterFactory.create()).build()
  val apiService = retrofit.create(ApiService::class.java)

  suspend fun login(username: String, password: String): JsonObject {
    return try {
      val loginRequest = ErpInterface.LoginInfo("login", username, password)
      val combineData: JsonObject = JsonObject()
      combineData.addProperty("command", "login")
      combineData.addProperty("user", username)
      combineData.addProperty("pass", password)
      //println(combineData)
      val response: Response<JsonObject> = apiService.login2(combineData)
      if (response.isSuccessful) {
        response.body() ?: throw Exception("Empty response body")
      } else {
        throw Exception("API request failed with code ${response.code()}")
      }
    } catch (e: Exception) {
      throw e
    }
  }

  suspend fun generalQuery(command: String, data: JsonObject, tokenString: String): JsonObject {
    return try {
      val combineData = JsonObject()
      combineData.addProperty("command", command)
      data.addProperty("token_string", tokenString)
      combineData.add("DATA", data)
      val response: Response<JsonObject> = apiService.fetchData(combineData)
      if (response.isSuccessful) {
        response.body() ?: throw Exception("Empty response body")
      } else {
        throw Exception("API request failed with code ${response.code()}")
      }
    } catch (e: Exception) {
      throw e
    }
  }
}

// Mock classes for GlobalVariable and ErpInterface to resolve references
class GlobalVariable {
    fun getServer(): String {
        return "http://14.160.33.94:5013/api"
    }
}

class ErpInterface {
    data class LoginInfo(val command: String, val user: String, val pass: String)
}
