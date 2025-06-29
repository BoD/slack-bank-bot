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

@file:Suppress("PropertyName")

package org.jraf.slackbankbot.nordigen.json

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

@Serializable
data class JsonErrorResponse(
  val summary: String,
  val detail: String,
  val status_code: Int? = null,
) : JsonTransactionsResponse()

@Serializable(with = JsonTransactionsResponseSerializer::class)
abstract class JsonTransactionsResponse

@Serializable
data class JsonTransactionsSuccessResponse(
  val transactions: JsonTransactionsTransactions,
) : JsonTransactionsResponse()

object JsonTransactionsResponseSerializer :
  JsonContentPolymorphicSerializer<JsonTransactionsResponse>(JsonTransactionsResponse::class) {
  override fun selectDeserializer(element: JsonElement): DeserializationStrategy<JsonTransactionsResponse> {
    return when {
      element.jsonObject.containsKey("summary") -> JsonErrorResponse.serializer()
      element.jsonObject.containsKey("transactions") -> JsonTransactionsSuccessResponse.serializer()
      else -> error("Unknown JSON response: $element")
    }
  }
}


@Serializable
data class JsonTransactionsTransactions(
  val booked: List<JsonTransactionsTransaction>,
)

@Serializable
data class JsonTransactionsTransaction(
  val internalTransactionId: String,
  val transactionId: String? = null,
  val bookingDate: String,
  val transactionAmount: JsonAmount,
  val remittanceInformationUnstructuredArray: List<String>,
)

