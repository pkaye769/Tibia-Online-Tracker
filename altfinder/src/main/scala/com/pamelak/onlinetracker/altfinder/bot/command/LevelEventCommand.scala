package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.*
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

import scala.jdk.CollectionConverters.*

class LevelEventCommand[F[_]: Async](repo: AltFinderRepoAlg[F]) extends Command[F] {

  private val actionOption = new OptionData(
    OptionType.STRING,
    "action",
    "add, remove, list, show, winners, rankups, reset",
    true,
    false
  ).addChoice("add", "add")
    .addChoice("remove", "remove")
    .addChoice("list", "list")
    .addChoice("show", "show")
    .addChoice("winners", "winners")
    .addChoice("rankups", "rankups")
    .addChoice("reset", "reset")

  private val rankChoices = LevelBracket.all.map { b =>
    new net.dv8tion.jda.api.interactions.commands.Command.Choice(b.displayName, b.displayName)
  }

  override val command: SlashCommandData =
    Commands
      .slash("levelevent", "Track and display guild leveling event progress")
      .setGuildOnly(true)
      .addOptions(
        List(
          actionOption,
          new OptionData(OptionType.STRING, "name", "Tibia guild name.", false, false),
          new OptionData(OptionType.STRING, "rank", "Level bracket to filter.", false, false)
            .addChoices(rankChoices.asJava)
        ).asJava
      )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val action = options.find(_.getName == "action").get.getAsString.trim.toLowerCase
    val guildName = options.find(_.getName == "name").map(_.getAsString.trim).filter(_.nonEmpty)
    val rankFilter = options.find(_.getName == "rank").map(_.getAsString.trim).filter(_.nonEmpty)
    val discordGuildId = event.getGuild.getId
    val embed = new EmbedBuilder().setColor(embedColour).setTitle("Level Event")

    action match {
      case "add" =>
        guildName match {
          case None =>
            Async[F].pure(embed.addField("Failed", "Guild name is required for add.", false).build())
          case Some(name) =>
            repo.upsertLevelEvent(LevelEventConfig(discordGuildId, name)).as(
              embed
                .addField("Added", s"Now tracking levels for **$name**.", false)
                .addField("Info", "Level data will be collected periodically. Use `show` to view progress.", false)
                .build()
            )
        }

      case "remove" =>
        guildName match {
          case None =>
            Async[F].pure(embed.addField("Failed", "Guild name is required for remove.", false).build())
          case Some(name) =>
            repo.removeLevelEvent(discordGuildId, name).map {
              case true  => embed.addField("Removed", name, false).build()
              case false => embed.addField("Not found", name, false).build()
            }
        }

      case "list" =>
        repo.listLevelEvents(discordGuildId).map { events =>
          if (events.isEmpty)
            embed
              .addField("No events", "Use `/levelevent action:add name:<guild>` to start tracking.", false)
              .build()
          else {
            val lines = events.take(20).map(e => s"${e.tibiaGuildName} (added ${e.createdAt.toLocalDate})")
            embed.addField("Tracked guilds", lines.mkString("\n"), false).build()
          }
        }

      case "show" =>
        resolveGuildName(guildName, discordGuildId).flatMap {
          case None =>
            Async[F].pure(
              embed.addField("No events", "Add one with `/levelevent action:add name:<guild>`.", false).build()
            )
          case Some(name) =>
            repo.getLevelSnapshots(discordGuildId, name).map { snapshots =>
              val charData = snapshotsToCharData(snapshots)
              if (charData.isEmpty)
                embed.addField("No data yet", "Level snapshots are still being collected.", false).build()
              else {
                embed.setTitle(s"Level Event: $name")
                val filtered = rankFilter match {
                  case Some(bracket) => charData.filter(c => LevelBracket.forLevel(c.startLevel).displayName == bracket)
                  case None          => charData
                }
                if (filtered.isEmpty)
                  embed.addField("No gains yet", "No characters in this bracket have gained levels.", false).build()
                else {
                  val grouped = filtered.groupBy(c => LevelBracket.forLevel(c.startLevel)).toList.sortBy(_._1.id)
                  for ((bracket, chars) <- grouped) {
                    val lines = chars.take(10).map(c =>
                      s"**${c.name}**: +${c.gained} levels (${c.startLevel} → ${c.endLevel})"
                    )
                    addFieldSafe(embed, s"Level ${bracket.displayName}", lines)
                  }
                  embed.build()
                }
              }
            }
        }

      case "winners" =>
        resolveGuildName(guildName, discordGuildId).flatMap {
          case None =>
            Async[F].pure(
              embed.addField("No events", "Add one with `/levelevent action:add name:<guild>`.", false).build()
            )
          case Some(name) =>
            repo.getLevelSnapshots(discordGuildId, name).map { snapshots =>
              val charData = snapshotsToCharData(snapshots)
              if (charData.isEmpty)
                embed.addField("No data yet", "Level snapshots are still being collected.", false).build()
              else {
                embed.setTitle(s"Level Event Winners: $name")
                val grouped = charData.groupBy(c => LevelBracket.forLevel(c.startLevel)).toList.sortBy(_._1.id)
                for ((bracket, chars) <- grouped) {
                  val prizeWinners = calculatePrizes(chars)
                  if (prizeWinners.nonEmpty) {
                    val lines = prizeWinners.map(w =>
                      s"**${w.name}**: +${w.gained} levels, ${formatPrize(w.prize)}"
                    )
                    addFieldSafe(embed, s":trophy: Level ${bracket.displayName}", lines)
                  }
                }
                embed.build()
              }
            }
        }

      case "rankups" =>
        resolveGuildName(guildName, discordGuildId).flatMap {
          case None =>
            Async[F].pure(
              embed.addField("No events", "Add one with `/levelevent action:add name:<guild>`.", false).build()
            )
          case Some(name) =>
            repo.getLevelSnapshots(discordGuildId, name).map { snapshots =>
              val charData = snapshotsToCharData(snapshots)
              val rankups = charData.filter { c =>
                LevelBracket.forLevel(c.startLevel).id != LevelBracket.forLevel(c.endLevel).id
              }.map { c =>
                val fromBracket = LevelBracket.forLevel(c.startLevel).displayName
                val toBracket = LevelBracket.forLevel(c.endLevel).displayName
                s"**${c.name}**: ${c.startLevel} → ${c.endLevel} ($fromBracket → $toBracket)"
              }
              if (rankups.isEmpty)
                embed.addField("No rankups yet", "No characters have crossed a level bracket yet.", false).build()
              else {
                embed.setTitle(s"Level Event Rankups: $name")
                addFieldSafe(embed, "Bracket advances", rankups)
                embed.build()
              }
            }
        }

      case "reset" =>
        guildName match {
          case None =>
            Async[F].pure(embed.addField("Failed", "Guild name is required for reset.", false).build())
          case Some(name) =>
            repo.clearLevelSnapshots(discordGuildId, name).as(
              embed
                .addField("Reset", s"Cleared all level snapshots for **$name**.", false)
                .addField("Info", "New data will be collected from scratch on the next poll.", false)
                .build()
            )
        }

      case _ =>
        Async[F].pure(embed.addField("Failed", s"Unknown action: $action", false).build())
    }
  }

  private def resolveGuildName(guildName: Option[String], discordGuildId: String): F[Option[String]] =
    guildName match {
      case Some(name) => Async[F].pure(Some(name))
      case None       => repo.listLevelEvents(discordGuildId).map(_.headOption.map(_.tibiaGuildName))
    }

  private def snapshotsToCharData(snapshots: List[LevelSnapshotRow]): List[LevelCharData] =
    snapshots
      .groupBy(_.characterName)
      .values
      .toList
      .flatMap { rows =>
        val sorted = rows.sortBy(_.recordedAt)
        val startLevel = sorted.head.level
        val endLevel = sorted.last.level
        val gained = endLevel - startLevel
        if (gained > 0) Some(LevelCharData(rows.head.characterName, startLevel, endLevel, gained)) else None
      }
      .sortWith { (c1, c2) =>
        if (c1.gained == c2.gained) c1.startLevel > c2.startLevel else c1.gained > c2.gained
      }

  private def calculatePrizes(chars: List[LevelCharData]): List[PrizeLevelWinner] = {
    val prizeDistribution = List(1000000, 750000)
    if (chars.isEmpty) return Nil
    val topN = chars.take(prizeDistribution.length)
    val winners = topN ++ chars.drop(prizeDistribution.length).takeWhile(_.gained == topN.last.gained)
    winners.zipWithIndex.map { case (winner, i) =>
      val tiedWith = winners.zipWithIndex.filter(_._1.gained == winner.gained)
      val totalPrize = tiedWith.map(_._2).flatMap(prizeDistribution.lift).sum
      val prize = if (tiedWith.nonEmpty) totalPrize / tiedWith.length else 0
      PrizeLevelWinner(winner.name, winner.gained, prize)
    }
  }

  private def formatPrize(prize: Int): String =
    if (prize >= 1000000) s"${prize / 1000000}kk" else s"${prize / 1000}k"

  private def addFieldSafe(embed: EmbedBuilder, title: String, lines: List[String]): Unit = {
    val chunks = lines.foldLeft(List(List.empty[String])) { (acc, line) =>
      val current = acc.head
      val tentative = (current :+ line).mkString("\n")
      if (tentative.length <= 1024) (current :+ line) :: acc.tail
      else List(line) :: acc
    }.reverse
    chunks.zipWithIndex.foreach { case (chunk, idx) =>
      val fieldTitle = if (idx == 0) title else s"$title (cont.)"
      val fieldValue = if (chunk.isEmpty) "None" else chunk.mkString("\n")
      embed.addField(fieldTitle, fieldValue, false)
    }
  }
}

case class PrizeLevelWinner(name: String, gained: Int, prize: Int)
