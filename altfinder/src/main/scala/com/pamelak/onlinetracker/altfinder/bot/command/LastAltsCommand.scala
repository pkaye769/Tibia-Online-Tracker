package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.service.AltFinderService
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.MessageEmbed.Field
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

class LastAltsCommand[F[_]: Async](service: AltFinderService[F]) extends Command[F] {

  given Logger[F] = Slf4jLogger.getLogger[F]

  override val command: SlashCommandData =
    Commands.slash("alts-last", "Repeat the last saved alt search").setGuildOnly(true)

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Alt finder (last search)")
    service.getLastSearch.flatMap {
      case None =>
        Async[F].pure(embedBuilder.addField("No saved search", "Run `/alts` once to save a search.", false).build())
      case Some(last) =>
        service.findAndPrintAlts(last.characters, last.from, last.to, last.distance, last.includeClashes).map { results =>

          val dateMessage = (results.searchedFrom, results.searchedTo) match {
            case (None, None) => "Max range"
            case (None, Some(t)) => s"Until ${t.toLocalDate()}"
            case (Some(f), None) => s"From ${f.toLocalDate()}"
            case (Some(f), Some(t)) => s"From ${f.toLocalDate()} until ${t.toLocalDate()}"
          }

          val bazaarScraperError = "Error accessing bazaar sources"
          val tradedField = (results.sales.allSales, results.sales.numberOfErrors) match
            case (Nil, 0) => None
            case (Nil, _) => Some(new Field("Couldn't check if traded", bazaarScraperError, false))
            case (_, _) =>
              val salesList = results.sales.characterSales.flatMap { s =>
                s.saleDates match
                  case Right(Nil) => None
                  case Right(dates) => Some(s"**${s.name}**: ${dates.map(_.toLocalDate).mkString(", ")}")
                  case Left(_) => Some(s"**${s.name}**: $bazaarScraperError")
              }
              val dateMessage = results.searchedFrom match
                case None => "Setting the `from` date to be the date of the latest sale."
                case Some(_) => "Using `from` date provided. Results may be inaccurate."
              val message = s"The following characters have been traded:\n${salesList.mkString("\n")}\n$dateMessage"
              Some(new Field("Traded character detected", message, false))

          embedBuilder.addField("Searched characters", results.searchedCharacters.mkString(", "), false)
            .addFieldOption(tradedField)
            .addField("Total logins", results.mainLogins.toString(), true)
            .addField("Date range", dateMessage, true).addField("\u200b", "\u200b", true)
            .addField("Adjacency distance", appendMinutes(last.distance.getOrElse(0)), true)
            .addField("Include clashes", last.includeClashes.toString, true).addField("\u200b", "\u200b", true)
            .addField("Possible matches", results.adjacencies.take(20).map(formatMatch).mkString("\n"), false).build()
        }
    }
  }

  extension (eb: EmbedBuilder)
    private def addFieldOption(field: Option[Field]) = field match
      case Some(f) => eb.addField(f)
      case None => eb

  private def appendMinutes(i: Int) = if (i == 1) s"$i minute" else s"$i minutes"

  private def formatMatch(adj: AltFinderService.CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    val tradeText =
      if (adj.recentTradeDates.nonEmpty) adj.recentTradeDates.map(_.toString).mkString(", ") else "none"
    val hiddenText = if (adj.hiddenLikely) s"yes (${adj.hiddenScore})" else s"no (${adj.hiddenScore})"
    s"$name | conf ${adj.confidence} | hidden $hiddenText | adj ${adj.adjacencies} | clashes $clashText | logins ${adj.logins} | traded $tradeText"
  }
}
