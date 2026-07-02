package de.champonthis.ghs.server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import de.champonthis.ghs.server.entity.Setting;

public interface SettingRepository extends JpaRepository<Setting, Long> {

}
