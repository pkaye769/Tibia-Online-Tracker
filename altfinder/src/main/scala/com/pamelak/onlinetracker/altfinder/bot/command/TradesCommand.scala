package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

import scala.jdk.CollectionConverters.*

class TradesCommand[F[_]: Async](service: AltFinderService[F]) extends Command[F] {
  override val command: SlashCommandData =
    Commands.slash("trades", "Check if characters were traded recently").setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "characters", "Character names (comma separated).", false, false),
        new OptionData(OptionType.STRING, "world", "World name to list recent trades (default Nefera).", false, false),
        new OptionData(OptionType.INTEGER, "lookback-days", "Lookback window in days (default 30).", false, false)
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val charactersRaw = options.find(_.getName == "characters").map(_.getAsString().trim).getOrElse("")
    val worldRaw = options.find(_.getName == "world").map(_.getAsString().trim).getOrElse("")
    val lookbackDays = options.find(_.getName == "lookback-days").map(_.getAsInt()).getOrElse(30).max(1).min(365)
    val names = charactersRaw.split(",").map(_.trim).filter(_.nonEmpty).toList.distinct
    val targetWorld = if (worldRaw.nonEmpty) worldRaw else sys.env.get("BAZAAR_WORLD").orElse(sys.env.get("WORLD")).getOrElse("Nefera")
    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Trade Check")

    if (names.nonEmpty) {
      service.checkTradedCharacters(names, lookbackDays).map { statuses =>
        val lines = statuses.map { row =>
          val dates =
            if (row.recentTradeDates.isEmpty) "none"
            else row.recentTradeDates.map(_.toString).mkString(", ")
          val errorText = if (row.hadError) " | check error" else ""
          val aliases = row.checkedNames.mkString(", ")
          s"${row.characterName} | traded $dates$errorText | checked names: $aliases"
        }
        embedBuilder
          .addField("Lookback", s"$lookbackDays day(s)", true)
          .addField("Characters", names.mkString(", "), false)
          .addField("Results", lines.mkString("\n"), false)
          .build()
      }
    } else {
      service.checkWorldTrades(targetWorld, lookbackDays).map {
        case Left(err) =>
          embedBuilder.addField("Failed", err.message, false).build()
        case Right(statuses) =>
          val lines = statuses.map { row =>
            val dates =
              if (row.recentTradeDates.isEmpty) "none"
              else row.recentTradeDates.map(_.toString).mkString(", ")
            val errorText = if (row.hadError) " | check error" else ""
            s"${row.characterName} | traded $dates$errorText"
          }
          val resultsText = if (lines.isEmpty) "No trades found in lookback window." else lines.mkString("\n")
          embedBuilder
            .addField("Lookback", s"$lookbackDays day(s)", true)
            .addField("World", targetWorld, true)
            .addField("Results", resultsText, false)
            .build()
      }
    }
  }
}
