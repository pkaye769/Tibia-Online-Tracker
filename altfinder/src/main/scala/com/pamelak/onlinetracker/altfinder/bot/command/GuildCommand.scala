package com.pamelak.onlinetracker.altfinder.bot.command

import cats.effect.kernel.Async
import cats.syntax.all.*
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

class GuildCommand[F[_]: Async](client: TibiaDataClientAlg[F]) extends Command[F] {

  override val command: SlashCommandData =
    Commands.slash("guild", "Show guild summary from TibiaData").setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "name", "Guild name.", true, false)
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val name = options.find(_.getName == "name").get.getAsString().trim
    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Guild")

    client.getGuild(name).attempt.map {
      case Left(err) =>
        embedBuilder.addField("Failed", s"${err.getMessage}", false).build()
      case Right(json) =>
        val (gName, world, members, online) = parseGuild(json, name)
        embedBuilder
          .addField("Guild", gName, true)
          .addField("World", world, true)
          .addField("Members", members.toString, true)
          .addField("Online", online.toString, true)
          .build()
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
