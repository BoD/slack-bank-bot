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

import kotlinx.coroutines.delay
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.jraf.klibslack.client.SlackClient
import org.jraf.klibslack.client.configuration.ClientConfiguration
import org.jraf.slackbankbot.arguments.Account
import org.jraf.slackbankbot.arguments.Arguments
import org.jraf.slackbankbot.nordigen.client.NordigenClient
import org.jraf.slackbankbot.nordigen.client.configuration.HttpConfiguration
import org.jraf.slackbankbot.nordigen.client.configuration.HttpLoggingLevel
import org.jraf.slackbankbot.util.logd
import org.jraf.slackbankbot.util.logi
import org.jraf.slackbankbot.util.logw
import java.math.BigDecimal
import kotlin.time.Duration.Companion.hours
import org.jraf.slackbankbot.nordigen.client.configuration.ClientConfiguration as NordigenClientConfiguration

private fun createNordigenClient(secretId: String, secretKey: String) = NordigenClient(
  NordigenClientConfiguration(
    secretId = secretId,
    secretKey = secretKey,
//    httpConfiguration = HttpConfiguration(httpProxy = HttpProxy(host = "localhost", port = 8888))
    httpConfiguration = HttpConfiguration(loggingLevel = HttpLoggingLevel.ALL)
  )
)

suspend fun main(args: Array<String>) {
  println("Hello World!")
  val arguments = Arguments(args)
  when {
    arguments.isBotSubcommand -> startBot(arguments.botSubcommand)
    arguments.isRenewSubcommand -> renew(arguments.renewSubcommand)
    else -> {
      println("Unknown subcommand")
      System.exit(-1)
    }
  }
}

private suspend fun renew(arguments: Arguments.Renew) {
  val nordigenClient = createNordigenClient(arguments.nordigenSecretId, arguments.nordigenSecretKey)
  val agreementId = nordigenClient.createEndUserAgreement(institutionId = arguments.institutionId).getOrThrow()
  logd("userAgreementId=$agreementId")
  val requisitionLink = nordigenClient.createRequisition(
    institutionId = arguments.institutionId,
    agreementId = agreementId,
  ).getOrThrow()
  logi("Go to this link: $requisitionLink")
}

private suspend fun startBot(arguments: Arguments.Bot) {
  val nordigenClient = createNordigenClient(arguments.nordigenSecretId, arguments.nordigenSecretKey)

  val slackClient = SlackClient.newInstance(ClientConfiguration("", arguments.slackAuthToken))

  val lastTransactions = mutableMapOf<Account, List<NordigenClient.Transaction>>()
  while (true) {
    try {
      var text = ""
      logd("accountArguments=${arguments.accounts}")
      for (account in arguments.accounts) {
        logd("account=$account")

        val transactionsResult = nordigenClient.getTransactions(account.id)
        transactionsResult.fold(
          onFailure = { error ->
            logw(error, "Error getting transactions")
            text += "_${account.name}_\n:warning: Error getting transactions: ${error.message}\n\n"
          },
          onSuccess = { transactions ->
            logd("transactions=$transactions")
            val newTransactions = transactions - (lastTransactions[account] ?: emptyList()).toSet()
            logd("newTransactions=$newTransactions")

            if (newTransactions.isNotEmpty()) {
              // Account name
              text += "_${account.name}_\n"

              // Transactions
              for (transaction in newTransactions) {
                val transactionText =
                  "${transaction.amount.formatted()} - ${transaction.label}\n"
                logd(transactionText)
                text += transactionText
              }

              // Spent/earned this month
              val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
              val thisMonthAmounts = transactions
                .filter { it.date.year == today.year && it.date.monthNumber == today.monthNumber }
                .map { it.amount }
              val spentThisMonth =
                thisMonthAmounts.filter { it.signum() < 0 }.reduceOrNull { acc, amount -> acc + amount } ?: BigDecimal.ZERO
              val earnedThisMonth =
                thisMonthAmounts.filter { it.signum() > 0 }.reduceOrNull { acc, amount -> acc + amount } ?: BigDecimal.ZERO
              val netThisMonth = earnedThisMonth + spentThisMonth
              text += ":sum: This month: ${netThisMonth.formatted()} (${spentThisMonth.formatted()} - ${earnedThisMonth.formatted()})\n"

              // Balance
              val balanceResult = nordigenClient.getBalance(account.id)
              text += if (balanceResult.isFailure) {
                val e = balanceResult.exceptionOrNull()!!
                logw(e, "Could not get balance for ${account.name}")
                ":warning: Could not get balance for _${account.name}_: ${e.message}\n\n"
              } else {
                ":moneybag: Current balance: ${balanceResult.getOrThrow().formatted(withEmoji = false)}\n\n"
              }
            }

            lastTransactions[account] = transactions
          }
        )
      }

      logd("text=$text")
      if (text.isNotEmpty()) {
        slackClient.chatPostMessage(text = text, channel = arguments.slackChannel)
      }

    } catch (t: Throwable) {
      logw(t, "Caught exception in main loop")
    }

    logd("Sleep 4 hours")
    delay(4.hours)
  }
}

private fun BigDecimal.emoji() = if (signum() < 0) "🔻" else ":small_green_triangle:"

private fun BigDecimal.formatted(withEmoji: Boolean = true) = if (withEmoji) {
  emoji() + " "
} else {
  ""
} + "*" + String.format("%.2f", this) + " €*"
