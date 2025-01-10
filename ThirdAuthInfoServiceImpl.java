import com.alibaba.fastjson.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.collect.Maps;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
@Slf4j
public class ThirdAuthInfoServiceImpl extends ServiceImpl<ThirdAuthInfoMapper, ThirdAuthInfo> implements ThirdAuthInfoService {

    private final ThirdAuthConfigurationProperties authConfigurationProperties;

    private final RestTemplate restTemplate;

    private static final ParameterizedTypeReference<ByteResultRes<ByteAuthInfoRes>> TYPE_REFERENCE = new ParameterizedTypeReference<ByteResultRes<ByteAuthInfoRes>>() {
    };

    private static final String COMMA = ",";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Boolean callback(String auth_code, String state) {
        Boolean result = Boolean.FALSE;
        if (StringUtils.isBlank(auth_code) || StringUtils.isBlank(state)) {
            return result;
        }
        ThirdAuthInfo thirdAuthInfo = new ThirdAuthInfo();
        thirdAuthInfo.setPlatform(ThirdPlatformEnum.BYTE_MKT);
        thirdAuthInfo.setAuthCode(auth_code);
        thirdAuthInfo.setAccountId(state);
        thirdAuthInfo.setValid(1);

        //先根据入参status查询是否存在已生效的auth_code
        ThirdAuthInfo exist = getOne(Wrappers.lambdaQuery(ThirdAuthInfo.class)
                .eq(ThirdAuthInfo::getPlatform, ThirdPlatformEnum.BYTE_MKT)
                .eq(ThirdAuthInfo::getAccountId, state)
                .eq(ThirdAuthInfo::getValid, 1), false);
        if (Objects.nonNull(exist)) {
            //修改已存在的auth_code为无效
            exist.setValid(0);
            updateById(exist);
        }
        //初始化access_token和refresh_token
        initToken(thirdAuthInfo);
        //新增
        result = save(thirdAuthInfo);
        return result;
    }

    private void initToken(ThirdAuthInfo authInfo) {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Content-Type", "application/json");

        Map<String, String> body = Maps.newHashMapWithExpectedSize(4);
        body.put("app_id", authConfigurationProperties.getByte_mkt_app_id());
        body.put("secret", authConfigurationProperties.getByte_mkt_secret());
        body.put("grant_type", authConfigurationProperties.getByte_mkt_grant_type());
        body.put("auth_code", authInfo.getAuthCode());

        log.info("初始化字节效果通access_token入参【{}】", JSON.toJSONString(body));
        RequestEntity<Map<String, String>> request = new RequestEntity<>(body, headers, HttpMethod.POST, URI.create(authConfigurationProperties.getByte_mkt_auth_url()));
        ResponseEntity<ByteResultRes<ByteAuthInfoRes>> responseEntity = restTemplate.exchange(request, TYPE_REFERENCE);
        log.info("初始化字节效果通access_token出参【{}】", JSON.toJSONString(responseEntity));
        if (Objects.nonNull(responseEntity.getBody()) && Objects.nonNull(responseEntity.getBody().getData())) {
            ByteAuthInfoRes authInfoRes = responseEntity.getBody().getData();
            authInfo.setAccessToken(authInfoRes.getAccess_token());
            authInfo.setRefreshToken(authInfoRes.getRefresh_token());
            authInfo.setAdvertiserIds(CollectionUtils.isNotEmpty(authInfoRes.getAdvertiser_ids()) ? String.join(COMMA, authInfoRes.getAdvertiser_ids()) : StringUtils.EMPTY);
        }
    }
}
