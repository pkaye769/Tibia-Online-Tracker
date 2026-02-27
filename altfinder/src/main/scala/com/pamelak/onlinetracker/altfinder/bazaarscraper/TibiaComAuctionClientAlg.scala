package com.pamelak.onlinetracker.altfinder.bazaarscraper

import java.time.Instant

trait TibiaComAuctionClientAlg[F[_]] {
  def getAuctionEnd(auctionId: Long): F[Option[Instant]]
}
