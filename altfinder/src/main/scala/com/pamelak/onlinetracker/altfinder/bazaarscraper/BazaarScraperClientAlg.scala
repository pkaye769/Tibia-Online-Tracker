package com.pamelak.onlinetracker.altfinder.bazaarscraper

trait BazaarScraperClientAlg[F[_]] {
  def searchCharacter(name: String): F[String]
  def searchWorld(world: String, pageSize: Int): F[String]
  def cooldownRemainingSeconds: F[Long]
}
