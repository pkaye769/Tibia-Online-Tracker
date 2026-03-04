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

class ClashesCommand[F[_]: Async](service: AltFinderService[F]) extends Command[F] {
  override val command: SlashCommandData =
    Commands.slash("clashes", "Find login clashes between two character sets").setGuildOnly(true).addOptions(
      List(
        new OptionData(OptionType.STRING, "characters", "Source names (comma separated).", true, false),
        new OptionData(OptionType.STRING, "targets", "Target names to check against (comma separated).", true, false),
        new OptionData(OptionType.STRING, "from", "From date YYYY-MM-DD.", false, false),
        new OptionData(OptionType.STRING, "to", "To date YYYY-MM-DD.", false, false),
        new OptionData(OptionType.INTEGER, "distance", "Adjacency distance minutes (default 0).", false, false)
      ).asJava
    )

  override def handleEvent(event: SlashCommandInteractionEvent): F[MessageEmbed] = {
    val options: List[OptionMapping] = event.getInteraction.getOptions.asScala.toList
    val characters = options.find(_.getName == "characters").map(_.getAsString()).getOrElse("")
      .split(",").map(_.trim).filter(_.nonEmpty).toList
    val targets = options.find(_.getName == "targets").map(_.getAsString()).getOrElse("")
      .split(",").map(_.trim).filter(_.nonEmpty).toList
    val from = options.find(_.getName == "from").flatMap(o => optionMappingToSSDateTime(o).toOption)
    val to = options.find(_.getName == "to").flatMap(o => optionMappingToSSDateTime(o).toOption)
    val distance = options.find(_.getName == "distance").map(_.getAsInt()).getOrElse(0).max(0)

    val embedBuilder = (new EmbedBuilder()).setColor(embedColour).setTitle("Clashes")
    if (characters.isEmpty || targets.isEmpty) {
      Async[F].pure(embedBuilder.addField("Failed", "Both characters and targets are required.", false).build())
    } else {
      service.findClashes(characters, targets, from, to, distance).map { results =>
        val lines =
          if (results.clashes.isEmpty) "No clashes found."
          else results.clashes.map { c =>
            val name = c.characterName.getOrElse("Unknown")
            s"$name: ${c.adjacencies} / ${c.clashes} / ${c.logins}"
          }.mkString("\n")
        embedBuilder
          .addField("Searched", results.searchedCharacters.mkString(", "), false)
          .addField("Checked against", results.checkedCharacters.mkString(", "), false)
          .addField("Distance", s"$distance minute(s)", true)
          .addField("Matches", lines, false)
          .build()
      }
    }
  }
}
