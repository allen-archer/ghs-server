package de.champonthis.ghs.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import de.champonthis.ghs.server.entity.GameCode;

public interface GameCodeRepository extends JpaRepository<GameCode, String> {

}
