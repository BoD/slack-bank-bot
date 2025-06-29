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
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.http.URLBuilder
import io.ktor.serialization.kotlinx.json.json
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import org.jraf.klibnanolog.logd
import org.jraf.slackbankbot.nordigen.client.configuration.ClientConfiguration
import org.jraf.slackbankbot.nordigen.client.configuration.HttpLoggingLevel
import org.jraf.slackbankbot.nordigen.json.JsonAmount
import org.jraf.slackbankbot.nordigen.json.JsonErrorResponse
import org.jraf.slackbankbot.nordigen.json.JsonTransactionsSuccessResponse
import java.math.BigDecimal

class NordigenClient(private val clientConfiguration: ClientConfiguration) {
  private val service: NordigenService by lazy {
    NordigenService(
      provideHttpClient(clientConfiguration),
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
          },
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
          proxy = ProxyBuilder.http(
            URLBuilder().apply {
              host = httpProxy.host
              port = httpProxy.port
            }.build(),
          )
        }
      }
      // Setup logging if requested
      if (clientConfiguration.httpConfiguration.loggingLevel != HttpLoggingLevel.NONE) {
        install(Logging) {
          logger = object : Logger {
            override fun log(message: String) {
              logd("http - $message")
            }
          }
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
    val internalId: String,
    val id: String?,
    val date: LocalDate,
    val amount: BigDecimal,
    val label: String,
  ) {
    // We get the same transactions multiple times with different internal ids, but same transactionId ¯\_(ツ)_/¯
    // So base equality on that fact.
    override fun equals(other: Any?): Boolean {
      if (this === other) return true
      if (javaClass != other?.javaClass) return false

      other as Transaction

      if (internalId == other.internalId) return true
      if (id != null && id == other.id) return true
      return false
    }

    override fun hashCode(): Int {
      if (id != null) {
        return id.hashCode()
      } else {
        return internalId.hashCode()
      }
    }
  }

  class NordigenServiceException(transactionsResponse: JsonErrorResponse) :
    Exception(transactionsResponse.toString())

  suspend fun getTransactions(accountId: String): Result<List<Transaction>> {
    val transactionsResult = runCatching { service.getTransactions(accountId) }
    if (transactionsResult.isFailure) {
      return Result.failure(transactionsResult.exceptionOrNull()!!)
    }
    return when (val response = transactionsResult.getOrThrow()) {
      is JsonErrorResponse -> Result.failure(NordigenServiceException(response))
      is JsonTransactionsSuccessResponse -> Result.success(
        response.transactions.booked.map { jsonTransaction ->
          Transaction(
            internalId = jsonTransaction.internalTransactionId,
            id = jsonTransaction.transactionId,
            date = LocalDate.parse(jsonTransaction.bookingDate),
            amount = jsonTransaction.transactionAmount.toBigDecimal(),
            label = jsonTransaction.remittanceInformationUnstructuredArray
              .sorted()
              .joinToString(" / ")
              .replace("\n", " ")
              .replace(Regex("\\s+"), " "),
          )
        }
          // Newest transactions are first, for Slack messages we want the opposite
          .reversed(),
      )

      else -> error("Unknown response: $response")
    }
  }

  private fun JsonAmount.toBigDecimal() = BigDecimal(amount)

  // {"balances": [{"balanceAmount": {"amount": "9567.73", "currency": "EUR"}, "balanceType": "expected", "referenceDate": "2025-06-10"}]}
  // {"balances": [{"balanceAmount": {"amount": "8016.42", "currency": "EUR"}, "balanceType": "expected", "referenceDate": "2025-06-09"}, {"balanceAmount": {"amount": "8016.42", "currency": "EUR"}, "balanceType": "closingBooked", "referenceDate": "2025-06-09"}]}
  // {"balances": [{"balanceAmount": {"amount": "28537.33", "currency": "EUR"}, "balanceType": "closingBooked"}]}
  suspend fun getBalance(accountId: String): Result<BigDecimal> {
    return runCatching {
      service.getBalances(accountId).balances.first().balanceAmount.toBigDecimal()
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
}
