package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
import com.pamelak.onlinetracker.altfinder.repo.AltFinderRepoAlg
import com.pamelak.onlinetracker.altfinder.repo.Model.GuildTrackConfig
import com.pamelak.onlinetracker.altfinder.tibiadata.TibiaDataClientAlg
import io.circe.Json
import net.dv8tion.jda.api.EmbedBuilder
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData

import scala.jdk.CollectionConverters.*

class GuildTrackCommand[F[_]: Async](client: TibiaDataClientAlg[F], repo: AltFinderRepoAlg[F]) extends Command[F] {

  private val actionOption = new OptionData(OptionType.STRING, "action", "add, remove, list, check", true, false)
    .addChoice("add", "add").addChoice("remove", "remove").addChoice("list", "list").addChoice("check", "check")

  override val command: SlashCommandData =
    Commands.slash("guildtrack", "Track Tibia guilds for this Discord server").setGuildOnly(true).addOptions(
      List(
        actionOption,
        new OptionData(OptionType.STRING, "name", "Tibia guild name.", false, false)
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val action = options.find(_.getName == "action").get.getAsString().trim.toLowerCase
    val guildName = options.find(_.getName == "name").map(_.getAsString().trim).filter(_.nonEmpty)
    val discordGuildId = event.getGuild.getId
    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Guild Track")

    action match {
      case "add" =>
        guildName match {
          case None => Async[F].pure(embedBuilder.addField("Failed", "Guild name is required for add.", false).build())
          case Some(name) =>
            repo.upsertTrackedGuild(GuildTrackConfig(discordGuildId, name)).as(
              embedBuilder.addField("Added/updated", name, false).build()
            )
        }

      case "remove" =>
        guildName match {
          case None => Async[F].pure(embedBuilder.addField("Failed", "Guild name is required for remove.", false).build())
          case Some(name) =>
            repo.removeTrackedGuild(discordGuildId, name).map {
              case true => embedBuilder.addField("Removed", name, false).build()
              case false => embedBuilder.addField("Not found", name, false).build()
            }
        }

      case "list" =>
        repo.listTrackedGuilds(discordGuildId).map { rows =>
          if (rows.isEmpty) embedBuilder.addField("No tracked guilds", "Use `/guildtrack action:add name:<guild>`.", false).build()
          else {
            val lines = rows.take(20).map(r => s"${r.tibiaGuildName} (updated ${r.updatedAt.toLocalDate})")
            embedBuilder.addField("Tracked guilds", lines.mkString("\n"), false).build()
          }
        }

      case "check" =>
        val namesF =
          guildName match {
            case Some(name) => Async[F].pure(List(name))
            case None => repo.listTrackedGuilds(discordGuildId).map(_.map(_.tibiaGuildName))
          }

        namesF.flatMap { names =>
          if (names.isEmpty) Async[F].pure(embedBuilder.addField("No tracked guilds", "Add one with `/guildtrack action:add`.", false).build())
          else {
            names.take(10).traverse { name =>
              client.getGuild(name).attempt.map {
                case Left(err) => s"$name | failed: ${Option(err.getMessage).getOrElse("unknown error")}"
                case Right(json) =>
                  val (_, world, members, online) = parseGuild(json, name)
                  s"$name | world $world | members $members | online $online"
              }
            }.map { lines =>
              embedBuilder.addField("Guild status", lines.mkString("\n"), false).build()
            }
          }
        }

      case _ =>
        Async[F].pure(embedBuilder.addField("Failed", s"Unknown action: $action", false).build())
    }
  }

  private def parseGuild(json: Json, fallbackName: String): (String, String, Int, Int) = {
    val guildCursor = json.hcursor.downField("guild")
    val name = guildCursor.get[String]("name").getOrElse(fallbackName)
    val world = guildCursor.get[String]("world").getOrElse("Unknown")

    val membersDirect = guildCursor.downField("members").as[List[Json]].toOption
    val membersNested = guildCursor.downField("members").downField("members").as[List[Json]].toOption
    val membersList = membersDirect.orElse(membersNested).getOrElse(Nil)

    val total = guildCursor.get[Int]("members_total").toOption
      .orElse(guildCursor.downField("members").get[Int]("members_total").toOption)
      .getOrElse(membersList.length)

    val online = guildCursor.get[Int]("members_online").toOption
      .orElse(guildCursor.downField("members").get[Int]("members_online").toOption)
      .getOrElse {
        membersList.count { m =>
          m.hcursor.get[String]("status").toOption.exists(_.equalsIgnoreCase("online"))
        }
      }

    (name, world, total, online)
  }
}
