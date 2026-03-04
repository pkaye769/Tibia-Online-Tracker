package com.pamelak.onlinetracker.altfinder.bot

import com.pamelak.onlinetracker.altfinder.bot.command.Command
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import cats.effect.kernel.Async
import cats.effect.std.Dispatcher
import cats.syntax.all.*
import org.typelevel.log4cats.slf4j.Slf4jLogger
import org.typelevel.log4cats.Logger

class BotListener[F[_]: Async](commands: List[Command[F]], dispatcher: Dispatcher[F]) extends ListenerAdapter {
  given Logger[F] = Slf4jLogger.getLogger[F]

  override def onSlashCommandInteraction(event: SlashCommandInteractionEvent): Unit = {
    commands.find(_.command.getName == event.getName) match {
      case Some(command) =>
        event.deferReply().queue(
          _ => dispatcher.unsafeRunAndForget {
            command.handleEvent(event)
              .flatMap { embed =>
                Async[F].delay(event.getHook.sendMessageEmbeds(embed).queue())
              }
              .handleErrorWith { e =>
                Logger[F].error(e)(s"Slash command failed: ${event.getName}") *>
                  Async[F].delay {
                    event.getHook
                      .sendMessage("Command failed. Try again in a moment.")
                      .setEphemeral(true)
                      .queue(
                        _ => (),
                        hookErr => dispatcher.unsafeRunAndForget(Logger[F].warn(hookErr)(s"Failed to send error reply for: ${event.getName}"))
                      )
                  }
              }
          },
          e => dispatcher.unsafeRunAndForget(Logger[F].warn(e)(s"Failed to defer slash command: ${event.getName}"))
        )
      case None =>
        event.reply(s"Command not found: ${event.getName}").setEphemeral(true).queue()
    }
  }

}
