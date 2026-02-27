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

class WorldCommand[F[_]: Async](client: TibiaDataClientAlg[F]) extends Command[F] {

  override val command: SlashCommandData =
    Commands.slash("world", "Show world summary from TibiaData").setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "name", "World name.", true, false)
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val worldName = options.find(_.getName == "name").get.getAsString().trim
    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("World")

    client.getWorld(worldName).attempt.map {
      case Left(err) =>
        embedBuilder.addField("Failed", s"${err.getMessage}", false).build()
      case Right(json) =>
        val summary = parseWorld(json, worldName)
        val eb = embedBuilder
          .addField("World", summary.name, true)
          .addField("Status", summary.status, true)
          .addField("Players Online", summary.playersOnline.toString, true)
          .addField("Location", summary.location, true)
          .addField("PvP", summary.pvpType, true)
          .addField("BattleEye", summary.battleye, true)

        if summary.topOnlinePlayers.nonEmpty then
          eb.addField("Top Online", summary.topOnlinePlayers.mkString("\n"), false).build()
        else eb.build()
    }
  }

  private case class WorldSummary(
      name: String,
      status: String,
      playersOnline: Int,
      location: String,
      pvpType: String,
      battleye: String,
      topOnlinePlayers: List[String]
  )

  private def parseWorld(json: Json, fallbackName: String): WorldSummary = {
    val worldCursor = json.hcursor.downField("world")
    val name = worldCursor.get[String]("name").getOrElse(fallbackName)
    val status = worldCursor.get[String]("status").getOrElse("unknown")
    val playersOnline = worldCursor.get[Int]("players_online").toOption
      .orElse(worldCursor.get[Double]("players_online").toOption.map(_.toInt))
      .getOrElse(0)
    val location = worldCursor.get[String]("location").getOrElse("Unknown")
    val pvp = worldCursor.get[String]("pvp_type").getOrElse("Unknown")
    val battleye = worldCursor.get[Boolean]("battleye_protected").toOption.map(b => if (b) "Yes" else "No")
      .getOrElse("Unknown")

    val onlinePlayers = worldCursor.downField("online_players").as[List[Json]].toOption.getOrElse(Nil)
    val top = onlinePlayers.take(10).flatMap { p =>
      val c = p.hcursor
      val pname = c.get[String]("name").toOption
      val lvl = c.get[Int]("level").toOption.orElse(c.get[Double]("level").toOption.map(_.toInt))
      val voc = c.get[String]("vocation").toOption
      pname.map { n =>
        val lv = lvl.map(_.toString).getOrElse("?")
        val v = voc.getOrElse("?")
        s"$n ($lv $v)"
      }
    }

    WorldSummary(name, status, playersOnline, location, pvp, battleye, top)
  }
}
