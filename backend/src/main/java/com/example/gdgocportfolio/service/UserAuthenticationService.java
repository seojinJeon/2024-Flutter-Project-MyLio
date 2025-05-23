package com.example.gdgocportfolio.service;

import com.example.gdgocportfolio.authorization.AuthorizationManager;
import com.example.gdgocportfolio.dto.*;
import com.example.gdgocportfolio.entity.*;
import com.example.gdgocportfolio.exceptions.*;
import com.example.gdgocportfolio.repository.*;
import com.example.gdgocportfolio.util.HashConvertor;
import com.example.gdgocportfolio.util.JwtProvider;
import org.json.JSONArray;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class UserAuthenticationService {

	private final UserRepository userRepository;
	private final UserRefreshTokenRepository userRefreshTokenRepository;
	private final UserAuthenticationRepository userAuthenticationRepository;
	private final ResumeRepository resumeRepository;
	private final CoverLetterRepository coverLetterRepository;

	private final AuthorizationManager authorizationManager;
	private final HashConvertor hashConvertor;
	private final JwtProvider jwtProvider;
	private final long expireTime;

	public UserAuthenticationService(UserRepository userRepository, UserRefreshTokenRepository userRefreshTokenRepository, UserAuthenticationRepository userAuthenticationRepository, ResumeRepository resumeRepository, CoverLetterRepository coverLetterRepository, AuthorizationManager authorizationManager, HashConvertor hashConvertor, JwtProvider jwtProvider) {
		this.userRepository = userRepository;
		this.userRefreshTokenRepository = userRefreshTokenRepository;
		this.userAuthenticationRepository = userAuthenticationRepository;
		this.resumeRepository = resumeRepository;
		this.coverLetterRepository = coverLetterRepository;
		this.authorizationManager = authorizationManager;
		this.hashConvertor = hashConvertor;
		this.jwtProvider = jwtProvider;
		this.expireTime = jwtProvider.getExpireTime();
	}

	public UserRefreshTokenInfoDto verifyUserRefreshToken(String refreshToken) {
		UserRefreshTokenInfoDto userRefreshTokenInfoDto;
		try {
			Map<String, String> result = jwtProvider.parseJwtToken(refreshToken, Set.of("uuid", "user_id", "exp", "iat"));
			long exp = Long.parseLong(result.get("exp"));
			if (exp < jwtProvider.getCurrentTime()) throw new InvalidJwtException("Expired token");
			userRefreshTokenInfoDto = new UserRefreshTokenInfoDto(UUID.fromString(result.get("uuid")), Long.parseLong(result.get("user_id")));
		} catch (Exception e) {
			throw new InvalidJwtException(e.toString());
		}
		return userRefreshTokenInfoDto;
	}

	public UserAccessTokenInfoDto verifyUserAccessToken(String accessToken) {
		UserAccessTokenInfoDto userAccessTokenInfoDto;
		try {
			Map<String, String> result = jwtProvider.parseJwtToken(accessToken, Set.of("email", "user_id", "authorization", "exp", "iat"));
			long exp = Long.parseLong(result.get("exp"));
			if (exp < jwtProvider.getCurrentTime()) throw new InvalidJwtException("Expired Token");
			List<String> permissions = new JSONArray(result.get("authorization")).toList().stream().map(Object::toString).toList();

			String email = result.get("email");
			String userId = result.get("user_id");
			if (userId == null || email == null) {
				throw new InvalidJwtException();
			}
			userAccessTokenInfoDto = new UserAccessTokenInfoDto(email, userId, permissions);
		} catch (Exception e) {
			throw new InvalidJwtException(e.getMessage());
		}
		return userAccessTokenInfoDto;
	}

	/**
	 * @return Return JWT tokens if user is authenticated, otherwise return null.
	 * @throws IncorrectPasswordException if password is incorrect
	 * @throws IllegalArgumentException if user is not found
	 */
	@Transactional
	public UserJwtDto generateUserJwtToken(final UserLoginRequestDto userLoginRequestDto) {
		UserAuthentication userAuthentication = userAuthenticationRepository.findByEmailEquals(userLoginRequestDto.getEmail()).orElseThrow(() -> new IllegalArgumentException("User not found"));
		if (!userAuthentication.getPassword().equals(hashConvertor.convertToHash(userLoginRequestDto.getPassword()))) {
			throw new IncorrectPasswordException();
		}

//		UUID uuid = UUID.randomUUID();
//		JwtProvider.Token accessToken = jwtProvider.generateJwtToken(Map.of(
//				"email", userAuthentication.getEmail(),
//				"user_id", userAuthentication.getUserId().toString(),
//				"authorization", new JSONArray(userAuthentication.getPermissions()).toString()
//		));
//		JwtProvider.Token refreshToken = jwtProvider.generateJwtToken(Map.of(
//				"uuid", uuid.toString(),
//				"user_id", String.valueOf(userAuthentication.getUserId())
//		));
//		RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity(uuid, userAuthentication.getUserId(), refreshToken.getExpire());
//		userRefreshTokenRepository.save(refreshTokenEntity);
//		return new UserJwtDto(accessToken.getToken(), refreshToken.getToken());
		return generateUserJwtToken(userAuthentication.getEmail(), userAuthentication.getUserId(), userAuthentication.getPermissions());
	}

	private UserJwtDto generateUserJwtToken(final String email, final Long userId, final Set<String> permissions) {
		UUID uuid = UUID.randomUUID();
		JwtProvider.Token accessToken = jwtProvider.generateJwtToken(Map.of(
				"email", email,
				"user_id", String.valueOf(userId),
				"authorization", (new JSONArray(permissions).toString())
		));
		JwtProvider.Token refreshToken = jwtProvider.generateJwtToken(Map.of(
				"uuid", uuid.toString(),
				"user_id", String.valueOf(userId)
		));
		RefreshTokenEntity refreshTokenEntity = new RefreshTokenEntity(uuid, userId, refreshToken.getExpire());
		userRefreshTokenRepository.save(refreshTokenEntity);
		return new UserJwtDto(accessToken.getToken(), refreshToken.getToken(), userId);
	}

	@Transactional
	public UserJwtDto refreshUserJwtToken(String refreshToken) {
		UserRefreshTokenInfoDto userRefreshTokenInfoDto = verifyUserRefreshToken(refreshToken);
		RefreshTokenEntity refreshTokenEntity = userRefreshTokenRepository.findById(userRefreshTokenInfoDto.getUuid()).orElseThrow(() -> new InvalidJwtException("Expired refresh token"));
		UserAuthentication userAuthentication = userAuthenticationRepository.findById(userRefreshTokenInfoDto.getUserId()).orElseThrow(() -> new InvalidJwtException("Invalid user id"));
		userRefreshTokenRepository.delete(refreshTokenEntity);

		return generateUserJwtToken(userAuthentication.getEmail(), userAuthentication.getUserId(), userAuthentication.getPermissions());
	}

	@Transactional
	public void registerUser(final UserRegisterRequestDto requestDTO) {
		if (userAuthenticationRepository.findByEmailEquals(requestDTO.getEmail()).isPresent()) {
			throw new UserExistsException();
		}

		User user = userRepository.findByPhoneNumber(requestDTO.getPhoneNumber());
		if (user != null) throw new UserExistsWithPhoneNumberException();

		user = new User();
		user.setPhoneNumber(requestDTO.getPhoneNumber());
		user.setName(requestDTO.getName());
		userRepository.save(user);

		UserAuthentication userAuthentication = new UserAuthentication();
		userAuthentication.setUserId(user.getUserId());
		userAuthentication.setEmail(requestDTO.getEmail());
		userAuthentication.setPassword(hashConvertor.convertToHash(requestDTO.getPassword()));
		userAuthentication.setEnabled(true);
		userAuthentication.setPermissions(Set.of("user.resume", "user.coverletter", "user.user"));
		userAuthenticationRepository.save(userAuthentication);
	}

	@Transactional
	public void deleteUser(final Long userId) {
		UserAuthentication userAuthentication = userAuthenticationRepository.findById(userId).orElseThrow(() -> new UserNotExistsException("Fail to find authentication information"));
		User user = userRepository.findById(userId).orElseThrow(() -> new UserNotExistsException("Fail to find user information"));
		List<RefreshTokenEntity> allByUserId = userRefreshTokenRepository.findAllByUserId(userId);
		List<Resume> resumes = resumeRepository.findByUserUserId(userId);
		List<CoverLetter> coverLetters = coverLetterRepository.findAllByUserUserId(userId);

		userAuthenticationRepository.delete(userAuthentication);
		userRepository.delete(user);
		userRefreshTokenRepository.deleteAll(allByUserId);
		resumeRepository.deleteAll(resumes);
		coverLetterRepository.deleteAll(coverLetters);
	}

	@Transactional
	public void editPassword(final UserEditPasswordDto dto) {
		UserAuthentication userAuthentication = userAuthenticationRepository.findById(dto.getUserId()).orElseThrow(() -> new UserNotExistsException("Cannot find user"));
		userAuthentication.setPassword(hashConvertor.convertToHash(dto.getPassword()));
	}
}
