package com.pamelak.onlinetracker.altfinder.tibiadata

import io.circe.Json

trait TibiaDataClientAlg[F[_]] {
  def getWorld(world: String): F[Json]
  def getGuild(name: String): F[Json]
  def getCharacter(name: String): F[Json]
}
