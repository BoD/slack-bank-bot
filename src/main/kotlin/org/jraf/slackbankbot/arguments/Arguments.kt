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

package org.jraf.slackbankbot.arguments

import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.ExperimentalCli
import kotlinx.cli.Subcommand
import kotlinx.cli.multiple
import kotlinx.cli.required
import kotlinx.cli.vararg

@OptIn(ExperimentalCli::class)
class Arguments(av: Array<String>) {
  private val parser = ArgParser("slackbankbot")

  var isBotSubcommand: Boolean = false
    private set

  inner class Bot : Subcommand("bot", "Run the Slack bot") {
    val nordigenSecretId: String by option(
      type = ArgType.String,
      fullName = "nordigen-secret-id",
      shortName = "n",
      description = "Nordigen secret id"
    ).required()

    val nordigenSecretKey: String by option(
      type = ArgType.String,
      fullName = "nordigen-secret-key",
      shortName = "k",
      description = "Nordigen secret key"
    ).required()

    val slackAuthToken: String by option(
      type = ArgType.String,
      fullName = "slack-auth-token",
      shortName = "s",
      description = "Slack auth token"
    ).required()

    val slackChannel: String by option(
      type = ArgType.String,
      fullName = "slack-channel",
      shortName = "c",
      description = "Slack channel"
    ).required()

    val ignoreInSpentEarned: List<String> by option(
      type = ArgType.String,
      fullName = "ignore-in-spent-earned",
      shortName = "i",
      description = "Transactions to ignore in spent/earned calculations (as a regex on the label)",
    ).multiple()

    private val accountsStr: List<String> by argument(
      type = ArgType.String,
      fullName = "accounts",
      description = "Accounts"
    ).vararg()

    val accounts: List<Account> get() = accountsStr.map(String::toAccount)
    override fun execute() {
      isBotSubcommand = true
    }
  }

  var isRenewSubcommand: Boolean = false
    private set

  inner class Renew : Subcommand("renew", "Renew the Nordigen token") {
    val nordigenSecretId: String by option(
      type = ArgType.String,
      fullName = "nordigen-secret-id",
      shortName = "n",
      description = "Nordigen secret id"
    ).required()

    val nordigenSecretKey: String by option(
      type = ArgType.String,
      fullName = "nordigen-secret-key",
      shortName = "k",
      description = "Nordigen secret key"
    ).required()

    val institutionId: String by option(
      type = ArgType.String,
      fullName = "institution-id",
      shortName = "i",
      description = "Institution id"
    ).required()

    override fun execute() {
      isRenewSubcommand = true
    }
  }

  val botSubcommand = Bot()
  val renewSubcommand = Renew()

  init {
    parser.subcommands(botSubcommand, renewSubcommand)
    parser.parse(av)
  }
}

data class Account(val name: String, val id: String)

private fun String.toAccount(): Account {
  val (name, id) = split(':')
  return Account(name, id)
}
