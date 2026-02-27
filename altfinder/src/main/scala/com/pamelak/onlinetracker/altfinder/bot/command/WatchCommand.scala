package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.WatchConfig
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

import java.time.{OffsetDateTime, ZoneId}
import scala.jdk.CollectionConverters.*

class WatchCommand[F[_]: Async](service: AltFinderService[F], repo: AltFinderRepoAlg[F]) extends Command[F] {

  given Logger[F] = Slf4jLogger.getLogger[F]

  private val actionOption = new OptionData(OptionType.STRING, "action", "add, remove, list, check", true, false)
    .addChoice("add", "add").addChoice("remove", "remove").addChoice("list", "list").addChoice("check", "check")

  override val command: SlashCommandData = Commands.slash("watch", "Manage suspected character watches")
    .setGuildOnly(true).addOptions(
      List(
        actionOption,
        new OptionData(OptionType.STRING, "character", "Character name to watch/check/remove.", false, false),
        new OptionData(
          OptionType.INTEGER,
          "distance",
          "Adjacency distance in minutes (default 0).",
          false,
          false
        ),
        new OptionData(
          OptionType.BOOLEAN,
          "include-clashes",
          "Include clashing logins (default false).",
          false,
          false
        ),
        new OptionData(
          OptionType.INTEGER,
          "threshold",
          "Confidence threshold (0-100, default 80).",
          false,
          false
        ),
        new OptionData(
          OptionType.INTEGER,
          "window-days",
          "Lookback window in days (default 30).",
          false,
          false
        )
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val action = options.find(_.getName == "action").get.getAsString().toLowerCase
    val characterRaw = options.find(_.getName == "character").map(_.getAsString())
    val distance = options.find(_.getName == "distance").map(_.getAsInt()).getOrElse(0).max(0)
    val includeClashes = options.find(_.getName == "include-clashes").map(_.getAsBoolean()).getOrElse(false)
    val threshold = options.find(_.getName == "threshold").map(_.getAsInt()).getOrElse(80).max(0).min(100)
    val windowDays = options.find(_.getName == "window-days").map(_.getAsInt()).getOrElse(30).max(1).min(365)
    val guildId = event.getGuild.getId
    val channelId = event.getChannel.getId

    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Watch")

    action match {
      case "add" =>
        characterRaw match {
          case None =>
            Async[F].pure(embedBuilder.addField("Failed", "Character is required for add.", false).build())
          case Some(raw) =>
            val name = normalizeName(raw)
            val cfg = WatchConfig(guildId, channelId, name, distance, includeClashes, threshold, windowDays)
            repo.upsertWatch(cfg).as(
              embedBuilder
                .addField("Added/updated", name, false)
                .addField("Distance", distance.toString, true)
                .addField("Include clashes", includeClashes.toString, true)
                .addField("Threshold", threshold.toString, true)
                .addField("Window (days)", windowDays.toString, true)
                .build()
            )
        }
      case "remove" =>
        characterRaw match {
          case None =>
            Async[F].pure(embedBuilder.addField("Failed", "Character is required for remove.", false).build())
          case Some(raw) =>
            val name = normalizeName(raw)
            repo.removeWatch(guildId, name).map {
              case true => embedBuilder.addField("Removed", name, false).build()
              case false => embedBuilder.addField("Not found", name, false).build()
            }
        }
      case "list" =>
        repo.listWatches(guildId).map { rows =>
          if rows.isEmpty then embedBuilder.addField("No watches", "Use `/watch action:add` to add one.", false).build()
          else {
            val lines = rows.map { w =>
              val last = w.lastAlertAt.map(_.toLocalDate.toString).getOrElse("never")
              s"${w.characterName} | threshold ${w.confidenceThreshold} | distance ${w.distance} | window ${w.windowDays}d | last alert $last"
            }
            embedBuilder.addField("Watches", lines.mkString("\n"), false).build()
          }
        }
      case "check" =>
        characterRaw match {
          case None =>
            Async[F].pure(embedBuilder.addField("Failed", "Character is required for check.", false).build())
          case Some(raw) =>
            val name = normalizeName(raw)
            val from = Some(OffsetDateTime.now(ZoneId.of("Europe/Berlin")).minusDays(windowDays.toLong))
            service.findAndPrintAlts(List(name), from, None, Some(distance), includeClashes).map { results =>
              val matches = results.adjacencies.filter(_.confidence >= threshold)
              val info =
                if matches.isEmpty then "No matches above threshold."
                else matches.take(20).map(formatMatch).mkString("\n")
              embedBuilder
                .addField("Character", name, false)
                .addField("Threshold", threshold.toString, true)
                .addField("Window (days)", windowDays.toString, true)
                .addField("Matches", info, false)
                .build()
            }
        }
      case _ =>
        Async[F].pure(embedBuilder.addField("Failed", s"Unknown action: $action", false).build())
    }
  }

  private def normalizeName(name: String): String = {
    name.trim.split("\\s+").filter(_.nonEmpty).map { part =>
      part.toLowerCase.capitalize
    }.mkString(" ")
  }

  private def formatMatch(adj: AltFinderService.CharacterAdjacencies): String = {
    val name = adj.characterName.getOrElse("Unknown")
    val clashText = if (adj.clashes < 0) "yes" else adj.clashes.toString
    val tradeText =
      if (adj.recentTradeDates.nonEmpty) adj.recentTradeDates.map(_.toString).mkString(", ") else "none"
    val hiddenText = if (adj.hiddenLikely) s"yes (${adj.hiddenScore})" else s"no (${adj.hiddenScore})"
    s"$name | conf ${adj.confidence} | hidden $hiddenText | adj ${adj.adjacencies} | clashes $clashText | logins ${adj.logins} | traded $tradeText"
  }
}
