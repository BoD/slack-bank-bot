/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2023-present Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.jraf.slackbankbot.nordigen.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.auth.Auth
import io.ktor.client.plugins.auth.providers.bearer
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.logging.DEFAULT
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import org.jraf.slackbankbot.nordigen.client.configuration.ClientConfiguration
import org.jraf.slackbankbot.nordigen.client.configuration.HttpLoggingLevel
import org.jraf.slackbankbot.nordigen.json.JsonAmount
import org.jraf.slackbankbot.nordigen.json.JsonErrorResponse
import org.jraf.slackbankbot.nordigen.json.JsonTransactionsSuccessResponse

class NordigenClient(private val clientConfiguration: ClientConfiguration) {
  private val service: NordigenService by lazy {
    NordigenService(
      provideHttpClient(clientConfiguration)
    )
  }

  private fun provideHttpClient(clientConfiguration: ClientConfiguration): HttpClient {
    return HttpClient {
      install(ContentNegotiation) {
        json(
          Json {
            ignoreUnknownKeys = true
            useAlternativeNames = false
            encodeDefaults = true
          }
        )
      }
      install(Auth) {
        bearer {
          refreshTokens {
            service.newToken(secretId = clientConfiguration.secretId, secretKey = clientConfiguration.secretKey)
          }
        }
      }
      install(HttpTimeout) {
        requestTimeoutMillis = 60_000
        connectTimeoutMillis = 60_000
        socketTimeoutMillis = 60_000
      }
      engine {
        // Setup a proxy if requested
        clientConfiguration.httpConfiguration.httpProxy?.let { httpProxy ->
          proxy = ProxyBuilder.http(URLBuilder().apply {
            host = httpProxy.host
            port = httpProxy.port
          }.build())
        }
      }
      // Setup logging if requested
      if (clientConfiguration.httpConfiguration.loggingLevel != HttpLoggingLevel.NONE) {
        install(Logging) {
          logger = Logger.DEFAULT
          level = when (clientConfiguration.httpConfiguration.loggingLevel) {
            HttpLoggingLevel.NONE -> LogLevel.NONE
            HttpLoggingLevel.INFO -> LogLevel.INFO
            HttpLoggingLevel.HEADERS -> LogLevel.HEADERS
            HttpLoggingLevel.BODY -> LogLevel.BODY
            HttpLoggingLevel.ALL -> LogLevel.ALL
          }
        }
      }
    }
  }

  data class Transaction(
    val id: String,
    val date: String,
    val amount: String,
    val label: String,
  )

  class NordigenServiceException(transactionsResponse: JsonErrorResponse) :
    Exception(transactionsResponse.toString())

  suspend fun getTransactions(accountId: String): Result<List<Transaction>> {
    return when (val response = service.getTransactions(accountId)) {
      is JsonErrorResponse -> Result.failure(NordigenServiceException(response))
      is JsonTransactionsSuccessResponse -> Result.success(
        response.transactions.booked.map {
          Transaction(
            id = it.internalTransactionId,
            date = it.bookingDate,
            amount = it.transactionAmount.toFormatted(),
            label = it.remittanceInformationUnstructuredArray.firstOrNull() ?: "?",
          )
        }
          // Newest transactions are first, for Slack messages we want the opposite
          .reversed()
      )

      else -> error("Unknown response: $response")
    }
  }

  suspend fun getBalance(accountId: String): Result<String> {
    return runCatching {
      service.getBalances(accountId).balances.first { it.balanceType == "closingBooked" }.balanceAmount.toFormatted()
    }
  }

  suspend fun createEndUserAgreement(institutionId: String): Result<String> {
    return runCatching {
      service.createEndUserAgreement(institutionId).id
    }
  }

  suspend fun createRequisition(institutionId: String, agreementId: String): Result<String> {
    return runCatching {
      service.createRequisition(institutionId, agreementId).link
    }
  }

  private fun JsonAmount.toFormatted() = "$amount $currency"
}
