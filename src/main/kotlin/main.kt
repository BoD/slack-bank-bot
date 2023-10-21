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

@file:Suppress("LoggingStringTemplateAsArgument")

import org.jraf.klibslack.client.SlackClient
import org.jraf.klibslack.client.configuration.ClientConfiguration
import org.jraf.slackbankbot.arguments.Account
import org.jraf.slackbankbot.arguments.Arguments
import org.jraf.slackbankbot.nordigen.client.NordigenClient
import org.slf4j.LoggerFactory
import org.slf4j.simple.SimpleLogger
import java.util.concurrent.TimeUnit
import org.jraf.slackbankbot.nordigen.client.configuration.ClientConfiguration as NordigenClientConfiguration

private val LOGGER = run {
  // This must be done before any logger is initialized
  System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "trace")
  System.setProperty(SimpleLogger.SHOW_DATE_TIME_KEY, "true")
  System.setProperty(SimpleLogger.DATE_TIME_FORMAT_KEY, "yyyy-MM-dd HH:mm:ss")

  LoggerFactory.getLogger("Main")
}

private fun createNordigenClient(secretId: String, secretKey: String) = NordigenClient(
  NordigenClientConfiguration(
    secretId = secretId,
    secretKey = secretKey,
//    httpConfiguration = HttpConfiguration(httpProxy = HttpProxy(host = "localhost", port = 8888))
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
  val agreementId = nordigenClient.createEndUserAgreement(institutionId = arguments.institutionId)
  LOGGER.debug("userAgreementId=$agreementId")
  val requisitionLink = nordigenClient.createRequisition(
    institutionId = arguments.institutionId,
    agreementId = agreementId,
  )
  LOGGER.info("Go to this link: $requisitionLink")
}

private suspend fun startBot(arguments: Arguments.Bot) {
  val nordigenClient = createNordigenClient(arguments.nordigenSecretId, arguments.nordigenSecretKey)

  val slackClient = SlackClient.newInstance(ClientConfiguration("", arguments.slackAuthToken))

  val lastTransactions = mutableMapOf<Account, List<NordigenClient.Transaction>>()
  while (true) {
    try {
      var text = ""
      LOGGER.debug("accountArguments=${arguments.accounts}")
      for (account in arguments.accounts) {
        LOGGER.debug("account=$account")

        val transactionsResult = nordigenClient.getTransactions(account.id)
        transactionsResult.fold(
          onFailure = { error ->
            LOGGER.warn("Error getting transactions", error)
            text += "_${account.name}_\n:warning: Error getting transactions: ${error.message}\n\n"
          },
          onSuccess = { transactions ->
            LOGGER.debug("transactions.size=${transactions.size}")
            LOGGER.debug("transactions=$transactions")
            val newTransactions = transactions - (lastTransactions[account] ?: emptyList()).toSet()
            LOGGER.debug("newTransactions.size=${newTransactions.size}")
            LOGGER.debug("newTransactions=$newTransactions")

            if (newTransactions.isNotEmpty()) {
              text += "_${account.name}_\n"
            }
            for (transaction in newTransactions) {
              val transactionText =
                "${if (transaction.amount.startsWith('-')) "🔻" else ":small_green_triangle:"} *${transaction.amount}* - ${transaction.label}\n"
              LOGGER.debug(transactionText)
              text += transactionText
            }
            lastTransactions[account] = transactions

            // Show balance if there was at least one transaction
            if (newTransactions.isNotEmpty()) {
              val balance = nordigenClient.getBalance(account.id)
              text += ":sum: _${account.name}_ balance: *${balance}*\n\n"
            }
          }
        )
      }

      LOGGER.debug("text=$text")
      if (text.isNotEmpty()) {
        slackClient.chatPostMessage(text = text, channel = arguments.slackChannel)
      }

    } catch (t: Throwable) {
      LOGGER.warn("Caught exception in main loop", t)
    }

    LOGGER.debug("Sleep 4 hours")
    TimeUnit.HOURS.sleep(4)
  }
}
