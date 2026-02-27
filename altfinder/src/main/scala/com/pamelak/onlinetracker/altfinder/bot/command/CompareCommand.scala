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
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.jdk.CollectionConverters.*

class CompareCommand[F[_]: Async](service: AltFinderService[F]) extends Command[F] {

  given Logger[F] = Slf4jLogger.getLogger[F]

  override val command: SlashCommandData = Commands.slash("compare", "Compare two characters' login history")
    .setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "character_a", "First character name.", true, false),
        new OptionData(OptionType.STRING, "character_b", "Second character name.", true, false),
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
          "distance",
          "Minutes between logoff/logon to count as adjacency (default 0).",
          false,
          false
        )
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val a = options.find(_.getName == "character_a").get.getAsString().trim
    val b = options.find(_.getName == "character_b").get.getAsString().trim
    val parseFrom = options.find(_.getName == "from").map(optionMappingToSSDateTime)
    val parseTo = options.find(_.getName == "to").map(optionMappingToSSDateTime)
    val distance = options.find(_.getName == "distance").map(_.getAsInt()).getOrElse(0).max(0)

    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Character comparison")

    val fromEither = parseFrom.map(_.map(Some(_))).getOrElse(Right(None))
    val toEither = parseTo.map(_.map(Some(_))).getOrElse(Right(None))

    (fromEither, toEither) match {
      case (Right(from), Right(to)) =>
        service.compareCharacters(a, b, from, to, distance).map { results =>
          val dateMessage = (from, to) match {
            case (None, None) => "Max range"
            case (None, Some(t)) => s"Until ${t.toLocalDate}"
            case (Some(f), None) => s"From ${f.toLocalDate}"
            case (Some(f), Some(t)) => s"From ${f.toLocalDate} until ${t.toLocalDate}"
          }

          embedBuilder
            .addField("Character A", a, true)
            .addField("Character B", b, true)
            .addField("Date range", dateMessage, false)
            .addField("Adjacency distance", s"$distance minute(s)", true)
            .addField("\u200b", "\u200b", true)
            .addField("A -> B", s"Adjacencies: ${results.aToB.adjacencies}\nClashes: ${results.aToB.clashes}\nLogins: ${results.aToB.logins}\nHidden: ${results.aToB.hiddenLikely} (${results.aToB.hiddenScore})", true)
            .addField("B -> A", s"Adjacencies: ${results.bToA.adjacencies}\nClashes: ${results.bToA.clashes}\nLogins: ${results.bToA.logins}\nHidden: ${results.bToA.hiddenLikely} (${results.bToA.hiddenScore})", true)
            .build()
        }
      case _ =>
        val errors = List(parseFrom, parseTo).flatten.flatMap(_.left.toOption).mkString("\n")
        Async[F].pure(embedBuilder.addField("Failed", errors, false).build())
    }
  }
}
