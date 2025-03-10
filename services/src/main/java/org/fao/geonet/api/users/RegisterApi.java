//=============================================================================
//===   Copyright (C) 2001-2021 Food and Agriculture Organization of the
//===   United Nations (FAO-UN), United Nations World Food Programme (WFP)
//===   and United Nations Environment Programme (UNEP)
//===
//===   This program is free software; you can redistribute it and/or modify
//===   it under the terms of the GNU General Public License as published by
//===   the Free Software Foundation; either version 2 of the License, or (at
//===   your option) any later version.
//===
//===   This program is distributed in the hope that it will be useful, but
//===   WITHOUT ANY WARRANTY; without even the implied warranty of
//===   MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
//===   General Public License for more details.
//===
//===   You should have received a copy of the GNU General Public License
//===   along with this program; if not, write to the Free Software
//===   Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301, USA
//===
//===   Contact: Jeroen Ticheler - FAO - Viale delle Terme di Caracalla 2,
//===   Rome - Italy. email: geonetwork@osgeo.org
//==============================================================================

package org.fao.geonet.api.users;

import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jeeves.server.context.ServiceContext;
import org.fao.geonet.api.API;
import org.fao.geonet.api.ApiUtils;
import org.fao.geonet.api.tools.i18n.LanguageUtils;
import org.fao.geonet.api.users.model.UserRegisterDto;
import org.fao.geonet.api.users.recaptcha.RecaptchaChecker;
import org.fao.geonet.domain.*;
import org.fao.geonet.kernel.security.SecurityProviderConfiguration;
import org.fao.geonet.kernel.setting.SettingManager;
import org.fao.geonet.kernel.setting.Settings;
import org.fao.geonet.repository.GroupRepository;
import org.fao.geonet.repository.UserGroupRepository;
import org.fao.geonet.repository.UserRepository;
import org.fao.geonet.util.MailUtil;
import org.fao.geonet.util.PasswordUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.validation.BindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import javax.servlet.http.HttpServletRequest;
import java.sql.SQLException;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

@EnableWebMvc
@Service
@RequestMapping(value = {
    "/{portal}/api/user"
})
@Tag(name = "users",
    description = "User operations")
public class RegisterApi {

    @Autowired
    LanguageUtils languageUtils;

    @Autowired(required=false)
    SecurityProviderConfiguration securityProviderConfiguration;

    @io.swagger.v3.oas.annotations.Operation(summary = "Create user account",
        description = "User is created with a registered user profile. username field is ignored and the email is used as " +
            "username. Password is sent by email. Catalog administrator is also notified.")
    @RequestMapping(
        value = "/actions/register",
        method = RequestMethod.PUT,
        produces = MediaType.TEXT_PLAIN_VALUE)
    @ResponseStatus(value = HttpStatus.CREATED)
    @ResponseBody
    public ResponseEntity<String> registerUser(
        @Parameter(description = "User details",
            required = true)
        @RequestBody
            UserRegisterDto userRegisterDto,
        @Parameter(hidden = true)
            BindingResult bindingResult,
        @Parameter(hidden = true)
            HttpServletRequest request)
        throws Exception {

        Locale locale = languageUtils.parseAcceptLanguage(request.getLocales());
        ResourceBundle messages = ResourceBundle.getBundle("org.fao.geonet.api.Messages", locale);

        if (securityProviderConfiguration != null && !securityProviderConfiguration.isUserProfileUpdateEnabled()) {
            return new ResponseEntity<>(messages.getString("security_provider_unsupported_functionality"), HttpStatus.PRECONDITION_FAILED);
        }

        ServiceContext context = ApiUtils.createServiceContext(request);

        SettingManager sm = context.getBean(SettingManager.class);
        boolean selfRegistrationEnabled = sm.getValueAsBool(Settings.SYSTEM_USERSELFREGISTRATION_ENABLE);
        if (!selfRegistrationEnabled) {
            return new ResponseEntity<>(String.format(
                messages.getString("self_registration_disabled")
            ), HttpStatus.PRECONDITION_FAILED);
        }

        boolean recaptchaEnabled = sm.getValueAsBool(Settings.SYSTEM_USERSELFREGISTRATION_RECAPTCHA_ENABLE);

        if (recaptchaEnabled) {
            boolean validRecaptcha = RecaptchaChecker.verify(userRegisterDto.getCaptcha(),
                sm.getValue(Settings.SYSTEM_USERSELFREGISTRATION_RECAPTCHA_SECRETKEY));
            if (!validRecaptcha) {
                return new ResponseEntity<>(
                    messages.getString("recaptcha_not_valid"), HttpStatus.PRECONDITION_FAILED);
            }
        }

        // Validate the user registration
        if (bindingResult.hasErrors()) {
            List<ObjectError> errorList = bindingResult.getAllErrors();

            StringBuilder sb = new StringBuilder();
            Iterator<ObjectError> it = errorList.iterator();
            while (it.hasNext()) {
                sb.append(messages.getString(it.next().getDefaultMessage()));
                if (it.hasNext()) {
                    sb.append(", ");
                }
            }

            return new ResponseEntity<>(sb.toString(), HttpStatus.PRECONDITION_FAILED);
        }

        final UserRepository userRepository = context.getBean(UserRepository.class);
        if (userRepository.findOneByEmail(userRegisterDto.getEmail()) != null) {
            return new ResponseEntity<>(String.format(
                messages.getString("user_with_that_email_found"),
                userRegisterDto.getEmail()
            ), HttpStatus.PRECONDITION_FAILED);
        }

        if (userRepository.findByUsernameIgnoreCase(userRegisterDto.getEmail()).size() != 0) {
            // username is ignored and the email is used as username in selfregister
            return new ResponseEntity<>(String.format(
                messages.getString("user_with_that_username_found"),
                userRegisterDto.getEmail()
            ), HttpStatus.PRECONDITION_FAILED);
        }

        User user = new User();

        // user.setUsername(userRegisterDto.getUsername());
        user.setName(userRegisterDto.getName());
        user.setOrganisation(userRegisterDto.getOrganisation());
        user.setProfile(Profile.findProfileIgnoreCase(userRegisterDto.getProfile()));
        user.getAddresses().add(userRegisterDto.getAddress());
        user.getEmailAddresses().add(userRegisterDto.getEmail());


        String password = User.getRandomPassword();
        user.getSecurity().setPassword(
            PasswordUtil.encode(context, password)
        );
        user.setUsername(user.getEmail());
        Profile requestedProfile = user.getProfile();
        user.setProfile(Profile.RegisteredUser);
        user = userRepository.save(user);

        Group targetGroup = getGroup(context);
        if (targetGroup != null) {
            UserGroup userGroup = new UserGroup().setUser(user).setGroup(targetGroup).setProfile(Profile.RegisteredUser);
            context.getBean(UserGroupRepository.class).save(userGroup);
        }


        String catalogAdminEmail = sm.getValue(Settings.SYSTEM_FEEDBACK_EMAIL);
        String subject = String.format(
            messages.getString("register_email_admin_subject"),
            sm.getSiteName(),
            user.getEmail(),
            requestedProfile
        );
        String message = String.format(
            messages.getString("register_email_admin_message"),
            user.getEmail(),
            requestedProfile,
            sm.getNodeURL(),
            sm.getSiteName()
        );
        if (!MailUtil.sendMail(catalogAdminEmail, subject, message, null, sm)) {
            return new ResponseEntity<>(String.format(
                messages.getString("mail_error")), HttpStatus.PRECONDITION_FAILED);
        }

        subject = String.format(
            messages.getString("register_email_subject"),
            sm.getSiteName(),
            user.getProfile()
        );
        message = String.format(
            messages.getString("register_email_message"),
            sm.getSiteName(),
            user.getUsername(),
            password,
            Profile.RegisteredUser,
            requestedProfile,
            sm.getNodeURL(),
            sm.getSiteName()
        );
        if (!MailUtil.sendMail(user.getEmail(), subject, message, null, sm)) {
            return new ResponseEntity<>(String.format(
                messages.getString("mail_error")), HttpStatus.PRECONDITION_FAILED);
        }

        return new ResponseEntity<>(String.format(
            messages.getString("user_registered"),
            user.getUsername()
        ), HttpStatus.CREATED);
    }

    Group getGroup(ServiceContext context) throws SQLException {
        final GroupRepository bean = context.getBean(GroupRepository.class);
        return bean.findById(ReservedGroup.guest.getId()).get();
    }
}
