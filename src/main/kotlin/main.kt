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
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import org.jraf.klibnanolog.logd
import org.jraf.klibnanolog.loge
import org.jraf.klibnanolog.logi
import org.jraf.klibnanolog.logw
import org.jraf.klibslack.client.SlackClient
import org.jraf.klibslack.client.configuration.ClientConfiguration
import org.jraf.slackbankbot.arguments.Account
import org.jraf.slackbankbot.arguments.Arguments
import org.jraf.slackbankbot.nordigen.client.NordigenClient
import org.jraf.slackbankbot.nordigen.client.configuration.HttpConfiguration
import org.jraf.slackbankbot.nordigen.client.configuration.HttpLoggingLevel
import java.math.BigDecimal
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import org.jraf.slackbankbot.nordigen.client.configuration.ClientConfiguration as NordigenClientConfiguration

private fun createNordigenClient(secretId: String, secretKey: String) = NordigenClient(
  NordigenClientConfiguration(
    secretId = secretId,
    secretKey = secretKey,
//    httpConfiguration = HttpConfiguration(httpProxy = HttpProxy(host = "localhost", port = 8888))
    httpConfiguration = HttpConfiguration(loggingLevel = HttpLoggingLevel.BODY),
  ),
)

suspend fun main(args: Array<String>) {
  logi("Hello World!")
  val arguments = Arguments(args)
  when {
    arguments.isBotSubcommand -> startBot(arguments.botSubcommand)
    arguments.isRenewSubcommand -> renew(arguments.renewSubcommand)
    else -> {
      loge("Unknown subcommand")
      exitProcess(-1)
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
      for ((i, account) in arguments.accounts.withIndex()) {
        logd(
          """
        

              -----------------------------------------------------------------------------------------
              account=$account (${i + 1}/${arguments.accounts.size})
              -----------------------------------------------------------------------------------------
              """.trimIndent(),
        )

        val transactionsResult = nordigenClient.getTransactions(account.id)
        transactionsResult.fold(
          onFailure = { error ->
            logw(error, "Error getting transactions")
            text += "_${account.name}_\n:warning: Error getting transactions: ${error.message}\n\n"
          },
          onSuccess = { transactions ->
            logd("transactions=${transactions.joinToString("\n")}")
            val transactions = transactions
              // We get the same transactions multiple times with different internal ids - so remove duplicates based on
              // equals/hashCode which is based on the transaction date, amount and label.
              .distinct()
            val lastTransactionsForAccount = lastTransactions[account].orEmpty().toSet()
            if (lastTransactionsForAccount.isEmpty()) {
              logd("No last transactions for account ${account.name}: skipping")
            } else {
              val newTransactions = transactions - lastTransactionsForAccount
              logd("newTransactions=${newTransactions.joinToString("\n")}")
              if (newTransactions.isNotEmpty()) {
                // Account name
                text += "_${account.name}_\n"

                // Transactions
                for (transaction in newTransactions) {
                  val transactionText =
                    "${transaction.amount.formatted(withEmoji = true)} - ${transaction.label}\n"
                  logd(transactionText)
                  text += transactionText
                }

                val today = Clock.System.now().toLocalDateTime(TimeZone.currentSystemDefault()).date
                val startOfThisMonth = today.minus(today.dayOfMonth - 1, DateTimeUnit.DAY)
                val startOf1MonthAgo = startOfThisMonth.minus(1, DateTimeUnit.MONTH)
                val startOf2MonthsAgo = startOfThisMonth.minus(2, DateTimeUnit.MONTH)

                // Spent/earned 2 month ago
                val (spent2MonthsAgo, earned2MonthsAgo, net2MonthsAgo) = transactions.spentEarnedNet(
                  startDateInclusive = startOf2MonthsAgo,
                  endDateExclusive = startOf1MonthAgo,
                )
                text += ":calendar: ${startOf2MonthsAgo.monthName()}: " +
                    "earned ${earned2MonthsAgo.formatted()}, " +
                    "spent ${spent2MonthsAgo.formatted()}, " +
                    "net ${net2MonthsAgo.formatted(withEmoji = true)}\n\n"

                // Spent/earned 1 month ago
                val (spentLastMonth, earnedLastMonth, netLastMonth) = transactions.spentEarnedNet(
                  startDateInclusive = startOf1MonthAgo,
                  endDateExclusive = startOfThisMonth,
                )
                text += ":calendar: ${startOf1MonthAgo.monthName()}: " +
                    "earned ${earnedLastMonth.formatted()}, " +
                    "spent ${spentLastMonth.formatted()}, " +
                    "net ${netLastMonth.formatted(withEmoji = true)}\n\n"

                // Spent/earned this month
                val (spentThisMonth, earnedThisMonth, netThisMonth) = transactions.spentEarnedNet(
                  startDateInclusive = startOfThisMonth,
                  endDateExclusive = null,
                )
                text += ":sum: This month: " +
                    "earned ${earnedThisMonth.formatted()}, " +
                    "spent ${spentThisMonth.formatted()}, " +
                    "net ${netThisMonth.formatted(withEmoji = true)}\n"

                // Balance
                val balanceResult = nordigenClient.getBalance(account.id)
                text += if (balanceResult.isFailure) {
                  val e = balanceResult.exceptionOrNull()!!
                  logw(e, "Could not get balance for ${account.name}")
                  ":warning: Could not get balance for _${account.name}_: ${e.message}\n\n"
                } else {
                  ":moneybag: Current balance: ${balanceResult.getOrThrow().formatted()}\n\n"
                }
              }
            }

            lastTransactions[account] = transactions
          },
        )
      }

      logd("text=$text")
      if (text.isNotEmpty()) {
        slackClient.chatPostMessage(text = text, channel = arguments.slackChannel)
      }

    } catch (t: Throwable) {
      logw(t, "Caught exception in main loop")
    }

    logd(
      """
      Sleep 6 hours
      ===========================================================================


      """.trimIndent(),
    )
    delay(6.hours + 10.minutes) // Add a few minutes because it looks like the backend is not exactly precise in its rate limiting.
  }
}

private fun LocalDate.monthName(): String =
  month.name.lowercase().replaceFirstChar { it.uppercase() }

private fun List<NordigenClient.Transaction>.spentEarnedNet(
  startDateInclusive: LocalDate,
  endDateExclusive: LocalDate?,
): Triple<BigDecimal, BigDecimal, BigDecimal> {
  val lastMonthAmounts =
    filter { it.date >= startDateInclusive && (endDateExclusive == null || it.date < endDateExclusive) }
      .map { it.amount }
  val spentLastMonth =
    lastMonthAmounts.filter { it.signum() < 0 }.reduceOrNull { acc, amount -> acc + amount }
      ?: BigDecimal.ZERO
  val earnedLastMonth =
    lastMonthAmounts.filter { it.signum() > 0 }.reduceOrNull { acc, amount -> acc + amount }
      ?: BigDecimal.ZERO
  val netLastMonth = earnedLastMonth + spentLastMonth
  return Triple(spentLastMonth, earnedLastMonth, netLastMonth)
}

private fun BigDecimal.emoji() = if (signum() < 0) "🔻" else ":small_green_triangle:"

private fun BigDecimal.formatted(withEmoji: Boolean = false) = if (withEmoji) {
  emoji() + " "
} else {
  ""
} + "*" + String.format("%.2f", this) + " €*"
