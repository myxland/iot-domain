

package com.gihub.surpassm.iot.mqtt.service.impl;

import cn.hutool.core.io.IoUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.asymmetric.KeyType;
import cn.hutool.crypto.asymmetric.RSA;
import com.gihub.surpassm.iot.mqtt.service.IAuthService;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.security.interfaces.RSAPrivateKey;
import java.util.Objects;

/**
 * 用户名和密码认证服务
 */
@Service
public class AuthService implements IAuthService {

	private RSAPrivateKey privateKey;

	@Override
	public boolean checkValid(String username, String password) {
		if (StrUtil.isBlank(username)) return false;
		if (StrUtil.isBlank(password)) return false;
		RSA rsa = new RSA(privateKey, null);
		String value = rsa.encryptBcd(username, KeyType.PrivateKey);
		return value.equals(password);
	}

	@PostConstruct
	public void init() {
		privateKey = IoUtil.readObj(Objects.requireNonNull(AuthService.class.getClassLoader().getResourceAsStream("keystore/auth-private.key")));
	}

}
