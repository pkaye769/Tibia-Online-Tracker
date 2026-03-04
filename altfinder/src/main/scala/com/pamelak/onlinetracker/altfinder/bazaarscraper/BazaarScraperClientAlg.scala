package com.pamelak.onlinetracker.altfinder.bazaarscraper

trait BazaarScraperClientAlg[F[_]] {
  def searchCharacter(name: String): F[String]
  def cooldownRemainingSeconds: F[Long]
}
