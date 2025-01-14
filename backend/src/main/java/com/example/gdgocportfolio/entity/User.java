package com.example.gdgocportfolio.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "user")
public class User {
	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long userId;

	private String name;
	private String phoneNumber;

	private LocalDateTime lastCoverLetterUpdateTime; // For checking if the user's coverLetter is up-to-date
}
