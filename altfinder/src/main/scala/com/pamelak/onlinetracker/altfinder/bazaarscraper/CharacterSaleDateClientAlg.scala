package com.pamelak.onlinetracker.altfinder.bazaarscraper

import java.time.Instant

/** An additional source of character sale/traded dates beyond exevopan.com.
  *
  * Implementations must fail gracefully: any error must be handled internally
  * and return an empty list rather than propagating exceptions.
  */
trait CharacterSaleDateClientAlg[F[_]] {

  /** Return all known sale/traded dates (as UTC Instants) for the given character name.
    * Returns an empty list if the character is not found or any error occurs.
    */
  def getSaleDates(name: String): F[List[Instant]]
}
