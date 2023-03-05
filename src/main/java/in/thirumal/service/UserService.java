package in.thirumal.service;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import in.thirumal.client.MessageServiceClient;
import in.thirumal.exception.BadRequestException;
import in.thirumal.exception.ResourceNotFoundException;
import in.thirumal.model.Contact;
import in.thirumal.model.ContactVerify;
import in.thirumal.model.Email;
import in.thirumal.model.GenericCd;
import in.thirumal.model.Login;
import in.thirumal.model.LoginHistory;
import in.thirumal.model.LoginUser;
import in.thirumal.model.LoginUserName;
import in.thirumal.model.Message;
import in.thirumal.model.Password;
import in.thirumal.model.Token;
import in.thirumal.model.UserResource;
import in.thirumal.repository.ContactRepository;
import in.thirumal.repository.GenericCdRepository;
import in.thirumal.repository.LoginHistoryRepository;
import in.thirumal.repository.LoginUserNameRepository;
import in.thirumal.repository.LoginUserRepository;
import in.thirumal.repository.PasswordRepository;
import in.thirumal.repository.TokenRepository;
import jakarta.validation.Valid;

/**
 * @author Thirumal
 *
 */
@Service
public class UserService {
	
	Logger logger = LoggerFactory.getLogger(UserService.class);
	//
	@Autowired
	private OAuth2AuthorizationConsentService oAuth2AuthorizationConsentService;
	//
	@Autowired
	private GenericCdRepository genericCdRepository;
	@Autowired
	private ContactRepository contactRepository;
	@Autowired
	private LoginUserRepository loginUserRepository;
	@Autowired
	private LoginUserNameRepository loginUserNameRepository;
	@Autowired
	private LoginHistoryRepository loginHistoryRepository;
	@Autowired
	private PasswordRepository passwordRepository;
	@Autowired
	private TokenRepository tokenRepository;
	@Autowired
	private MessageServiceClient messageServiceClient;
	@Autowired
	PasswordEncoder passwordEncoder;
	
	//
	@Value("${notification.email.sender}")
	String emailSender;
	@Value("${notification.sms.sender}")
	String smsSender;
	//
	/**
	 * Create new account for the user
	 * @param userResource
	 * @return 
	 */
	@Transactional
	public UserResource createAccount(UserResource userResource) {
		logger.debug("Creating new user.... {}", userResource);
		// Validation
		List<GenericCd> genericCds = genericCdRepository.list("contact", GenericCd.DEFAULT_LOCALE_CD);
		validateEmailAndPhoneNumber(userResource, genericCds);
		// Login user create
		Long loginUserId = loginUserRepository.save(LoginUser.builder().dateOfBirth(userResource.getDateOfBirth()).build());
		LoginUser loginUser = loginUserRepository.findById(loginUserId);
		logger.debug("Created login user id {}", loginUser);
		if (Objects.isNull(loginUser)) {
			throw new ResourceNotFoundException("Not able create an account, Contact support");
		}
		// Consent		
		oAuth2AuthorizationConsentService.save(OAuth2AuthorizationConsent
				.withId(userResource.getRegisteredClientId(), loginUser.getLoginUuid().toString())
				.authorities(t->t.addAll(userResource.getAuthorities()))
				.build());
		// User Name
		loginUserNameRepository.save(LoginUserName.builder().loginUserId(loginUserId)
				.firstName(userResource.getFirstName()).middleName(userResource.getMiddleName()).lastName(userResource.getLastName()).build());
		// Contact (i.e User ID to login)
		var contacts = new ArrayList<Contact>();
		contacts.add(Contact.builder().contactCd(Contact.EMAIL).loginUserId(loginUserId).loginId(userResource.getEmail()).build());
		contacts.add(Contact.builder().contactCd(Contact.PHONE_NUMBER).loginUserId(loginUserId)
				.loginId(userResource.getPhoneNumber()).build());
		contactRepository.saveAll(contacts);
		// Password
		passwordRepository.save(Password.builder().loginUserId(loginUserId).secretKey(passwordEncoder.encode(userResource.getPassword())).build());
		// Token - 
		for (Contact contact : contactRepository.findByLoginId(Set.of(userResource.getEmail(), userResource.getPhoneNumber()))) {
			String otp = generateOtp(6);
			sendOtp(userResource.getFirstName(), contact, otp, Email.SIGNUP_FTL_TEMPLATE);
			tokenRepository.save(Token.builder().contactId(contact.getContactId()).otp(passwordEncoder.encode(otp))
					.expiresOn(OffsetDateTime.now().plusMinutes(5)).build());
			
		}
		return get(loginUser.getLoginUuid());
	}

	private void sendOtp(String name, Contact contact, String otp, String template) {
		logger.debug("Sending OTP {} to {}", otp, contact.getLoginId());
		Message status = null ;
		if (Contact.PHONE_NUMBER.equals(contact.getContactCd())) {
			String message = "Dear " + name + ", Your OTP to SignUp is " + otp 
					+ " and valid for 5 minutes. Do not disclose it to anyone for security reasons.";
			status = messageServiceClient.send(Message.builder().sender(smsSender).receiver(Set.of(contact.getLoginId()))
					.information(message).messageOf(contact.getLoginUserId()).build());
		} else if (Contact.EMAIL.equals(contact.getContactCd())) {
			status = messageServiceClient.send(new Email(emailSender, Set.of(contact.getLoginId()), template, 
				Map.of("name", name, "otp", otp), "Account Verification OTP " + otp,  contact.getLoginUserId()));
		} 
		logger.debug("Email/SMS status {}", status);
	}

	/**
	 * Validation 
	 * 	-	REGEX
	 *  -	User Duplication 
	 * @param userResource
	 * @param genericCds
	 */
	private void validateEmailAndPhoneNumber(UserResource userResource, List<GenericCd> genericCds) {
		logger.debug("E-mail and phone number validation");
		// E-mail validation
		validateWithRegex(genericCds, Contact.EMAIL, userResource.getEmail(), "The Requested E-Mail is not vaild");
		//Phone Number validation
		validateWithRegex(genericCds, Contact.PHONE_NUMBER, userResource.getPhoneNumber(), "The Requested Phone Number is not vaild");	
		// User Duplication
		List<Contact> contacts = contactRepository.findByLoginId(Set.of(userResource.getEmail(), userResource.getPhoneNumber()));
		if (!contacts.isEmpty()) {
			String contact = contacts.stream().map(Contact::getLoginId).collect(Collectors.joining(", "));
			throw new BadRequestException("Account for " + contact + " is already available, please login or use forgot password");
		}
	}
	
	private void validateWithRegex(List<GenericCd> genericCds, Long codeCd, String value, String errorMessage) {
		Optional<String> regex = genericCds.stream()
				.filter(g->codeCd.equals(g.getCodeCd())).map(GenericCd::getRegex).findFirst();
		if (regex.isPresent() && !validateRegex(regex.get(), value)) {
			logger.debug("Regex {} is used for {}", regex.get(), value);
			throw new BadRequestException(errorMessage);
		}
	}	
	
	private boolean validateRegex(String regex, String value) {
		return Pattern.compile(regex).matcher(value).matches();
	}

	public UserResource get(UUID loginUuid) {
		logger.debug("Getting the user {}", loginUuid);
		LoginUser loginUser = loginUserRepository.findByUuid(loginUuid);
		if (Objects.isNull(loginUser)) {
			throw new ResourceNotFoundException("The requested user " + loginUuid + " is not available");
		}
		LoginUserName loginUserName = loginUserNameRepository.findByLoginUserId(loginUser.getLoginUserId());
		// Contact
		List<Contact> contacts = contactRepository.findAllByLoginUserId(loginUser.getLoginUserId());
		return buildUserResource(loginUser, loginUserName, contacts);
	}
	

	private UserResource buildUserResource(LoginUser loginUser, LoginUserName loginUserName, List<Contact> contacts) {
		UserResource userResource = new UserResource();
		// Login User
		userResource.setLoginUuid(loginUser.getLoginUuid());
		userResource.setDateOfBirth(loginUser.getDateOfBirth());
		userResource.setAccountCreatedOn(loginUser.getRowCreatedOn());
		// Login User Name
		userResource.setFirstName(loginUserName.getFirstName());
		userResource.setMiddleName(loginUserName.getMiddleName());
		userResource.setLastName(loginUserName.getLastName());
		// Contact
		for (Contact contact : contacts) {
			if (contact.getEndTime().isBefore(OffsetDateTime.now())) {
				continue;
			}
			if (Contact.EMAIL.equals(contact.getContactCd())) {
				userResource.setEmail(contact.getLoginId());
			}
			if (Contact.PHONE_NUMBER.equals(contact.getContactCd())) {
				userResource.setPhoneNumber(contact.getLoginId());
			}
		}
		return userResource;
	}

	public boolean changePassword() {
		return false;
	}

	public Object login(@Valid Login login) {
		// TODO Auto-generated method stub
		return null;
	}

	/**
	 * Generate OTP
	 * @param otpLength
	 * @return OTP
	 */
	private String generateOtp(int otpLength) {
		StringBuilder generatedOTP = new StringBuilder();
		SecureRandom secureRandom = new SecureRandom();
		try {
		    secureRandom = SecureRandom.getInstance(secureRandom.getAlgorithm());
		    for (int i = 0; i < otpLength; i++) {
		        generatedOTP.append(secureRandom.nextInt(9));
		    }
		} catch (NoSuchAlgorithmException e) {
		    e.printStackTrace();
		}
		logger.debug("Generated OTP {}", generatedOTP);
		return generatedOTP.toString();
	}

	@Transactional
	public boolean verifyContact(ContactVerify contactVerify) {
		logger.debug("Verifying contact account {}", contactVerify);
		Contact contact = contactRepository.findActiveLoginIdByLoginId(contactVerify.getContact());
		String errorMessage = "The requested login ID ";
		if (Objects.isNull(contact)) {
			errorMessage += contactVerify.getContact() + " is not available in the database";
			logger.debug(errorMessage);
			throw new ResourceNotFoundException(errorMessage);
		} else if (Objects.nonNull(contact.getVerifiedOn())) {
			errorMessage += contact.getLoginId() + " is already Verified on " + contact.getVerifiedOn();
			logger.debug(errorMessage);
			throw new BadRequestException(errorMessage);
		}
		// Check the OTP
		Token token = tokenRepository.findByContactId(contact.getContactId());
		if (Objects.isNull(token)) {
			errorMessage += contactVerify.getContact() + " has not requested OTP / it's expired";
			logger.debug(errorMessage);
			throw new BadRequestException(errorMessage);
		}
		if (!passwordEncoder.matches(contactVerify.getOtp(), token.getOtp())) {
			errorMessage = "OTP is not matched";
			logger.debug(errorMessage);
			throw new BadRequestException(errorMessage);
		}
		contact.setVerifiedOn(OffsetDateTime.now());
		return contactRepository.verify(contact) == 1;
	}

	/**
	 * First time OTP request 
	 * Used in Forgot password 1st OTP request & change user id
	 * @param emailId
	 * @param otp
	 * @return OTP
	 */
	@Transactional
	public boolean requestOtp(String loginId, String template) {
		logger.debug("The {} requested for OTP", loginId);
		Contact contact = contactRepository.findActiveLoginIdByLoginId(loginId);
		String errorMessage;
		if (contact == null) {
			errorMessage = "The requested login ID : " + loginId + " is not available in the system";
			logger.debug(errorMessage);
			throw new ResourceNotFoundException(errorMessage);
		}
		Token existToken = tokenRepository.findByContactId(contact.getContactId());
		if (existToken != null) {
			lastActiveTokenValidTime.applyAsLong(existToken);
		}
		String otp = generateOtp(5);
		tokenRepository.save(Token.builder().contactId(contact.getContactId()).otp(passwordEncoder.encode(otp))
				.expiresOn(OffsetDateTime.now().plusMinutes(Token.EXPIRY_TIME_IN_MINUTES)).build());
		LoginUserName loginUserName = loginUserNameRepository.findByLoginUserId(contact.getLoginUserId());
		sendOtp(loginUserName.getFirstName(), contact, otp, template);
		return true;
	}
	
	ToLongFunction<Token> lastActiveTokenValidTime = token -> {
		Duration duration = Duration.between(token.getExpiresOn().toLocalDateTime(), OffsetDateTime.now(Clock.systemUTC()).toLocalDateTime());
		if (duration.getSeconds() < 0) {
			String errorMessage = "Too many request in few minutes, the last token issued on " + token.getRowCreatedOn();
			logger.debug(errorMessage);
			throw new BadRequestException(errorMessage);
		}
		return duration.getSeconds();		
	};
	
	
	public List<LoginHistory> loginHistories(UUID loginUuid, int page, int size) {
		logger.debug("Listing login histories {} from page {} to {}", loginUuid, page, size);
		LoginUser loginUser = loginUserRepository.findByUuid(loginUuid);
		if (Objects.isNull(loginUser) ) {
			throw new ResourceNotFoundException("The reuested user is not present in the database");
		}
		return loginHistoryRepository.list(loginUser.getLoginUserId(), size, ((page - 1) * size));
	}
	
	
}
