package com.awad.emailclientai.modules.user.mapper;

import com.awad.emailclientai.modules.user.entity.User;
import com.awad.emailclientai.modules.user.dto.response.UserResponse;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface UserMapper {

    UserResponse toResponse(User user);
}