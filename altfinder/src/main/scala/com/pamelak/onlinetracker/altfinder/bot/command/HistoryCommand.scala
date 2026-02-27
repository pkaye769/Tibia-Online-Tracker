package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import com.pamelak.onlinetracker.altfinder.repo.Model.OnlineDateSegment
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.jdk.CollectionConverters.*

class HistoryCommand[F[_]: Async](service: AltFinderService[F]) extends Command[F] {

  given Logger[F] = Slf4jLogger.getLogger[F]

  override val command: SlashCommandData = Commands.slash("history", "Show login history for a character")
    .setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "character", "Character name to fetch history for.", true, false),
        new OptionData(
          OptionType.STRING,
          "from",
          "Start date (YYYY-MM-DD). Uses Server Save time.",
          false,
          false
        ),
        new OptionData(
          OptionType.STRING,
          "to",
          "End date (YYYY-MM-DD). Uses Server Save time.",
          false,
          false
        ),
        new OptionData(
          OptionType.INTEGER,
          "limit",
          "Max number of history rows (default 20, max 50).",
          false,
          false
        )
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val name = options.find(_.getName == "character").get.getAsString().trim
    val parseFrom = options.find(_.getName == "from").map(optionMappingToSSDateTime)
    val parseTo = options.find(_.getName == "to").map(optionMappingToSSDateTime)
    val limit = options.find(_.getName == "limit").map(_.getAsInt()).getOrElse(20).min(50).max(1)

    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Login history")

    val fromEither = parseFrom.map(_.map(Some(_))).getOrElse(Right(None))
    val toEither = parseTo.map(_.map(Some(_))).getOrElse(Right(None))

    (fromEither, toEither) match {
      case (Right(from), Right(to)) =>
        service.getHistory(name, from, to, limit).map { rows =>
          val dateMessage = (from, to) match {
            case (None, None) => "Max range"
            case (None, Some(t)) => s"Until ${t.toLocalDate}"
            case (Some(f), None) => s"From ${f.toLocalDate}"
            case (Some(f), Some(t)) => s"From ${f.toLocalDate} until ${t.toLocalDate}"
          }

          val lines =
            if (rows.isEmpty) List("No history found.")
            else rows.map(formatSegment)

          embedBuilder
            .addField("Character", name, false)
            .addField("Date range", dateMessage, true)
            .addField("Rows", rows.length.toString, true)
            .addField("History", lines.mkString("\n"), false)
            .build()
        }
      case _ =>
        val errors = List(parseFrom, parseTo).flatten.flatMap(_.left.toOption).mkString("\n")
        Async[F].pure(embedBuilder.addField("Failed", errors, false).build())
    }
  }

  private def formatSegment(seg: OnlineDateSegment): String =
    s"${seg.start.toLocalDate} ${seg.onlineDiscordFormat}"
}
