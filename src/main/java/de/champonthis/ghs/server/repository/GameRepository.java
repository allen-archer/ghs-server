package de.champonthis.ghs.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import de.champonthis.ghs.server.entity.Game;

public interface GameRepository extends JpaRepository<Game, Long> {

}
