package com.pamelak.onlinetracker.tracker.tibiadata

import com.pamelak.onlinetracker.tracker.tibiadata.response.*

trait TibiaDataClientAlg[F[_]] {
  def getWorld(world: String): F[WorldResponse]

  def getCharacter(name: String): F[CharacterResponse]
}
