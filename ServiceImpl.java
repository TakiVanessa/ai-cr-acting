import code.byted.cdp.ApiClient;
import code.byted.cdp.ApiException;
import code.byted.cdp.model.OnlineGetUserProfileRequest;
import code.byted.cdp.model.OnlineUserProfileRequest;
import code.byted.cdp.model.OnlineUserProfileRespWithPrivacy;
import code.byted.cdp.openapi.OnlineApi;
import com.alibaba.excel.util.StringUtils;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nttdata.redis.starter.client.RedisOps;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@Slf4j
public class CDPServiceImpl implements CDPService {
    @Resource
    private CDPConfig cdpConfig;

    @Override
    public CDPLeadsTagsProd getLeadsTags(String phone) {
        String cdpTagsKey = String.format(RedisConstants.CDP_TAGS_KEY.getKey(), phone);

        CDPLeadsTagsProd cdpLeadsTagsProd = RedisOps.get(cdpTagsKey, CDPLeadsTagsProd.class);
        if(cdpLeadsTagsProd != null){
            return cdpLeadsTagsProd;
        } else {
            cdpLeadsTagsProd = new CDPLeadsTagsProd();
        }

        try {
            Class clazz = "prod".equals(cdpConfig.getEnvironment()) ? CDPLeadsTagsProd.class : CDPLeadsTags.class;
            Map<String, String> tagsAndPropertiesMap = new HashMap<>();

            OnlineApi api = new OnlineApi(new ApiClient(cdpConfig.getAccessKeyID(), cdpConfig.getSecretAccessKey(), cdpConfig.getBasePath()));
            OnlineGetUserProfileRequest body = new OnlineGetUserProfileRequest();
            body.setProject(cdpConfig.getProject());

            OnlineUserProfileRequest profileRequest = new OnlineUserProfileRequest();
            profileRequest.setIdType("phone_all");
            profileRequest.setId(phone);
            profileRequest.setTags(cdpConfig.parsePropertiesOrTags(cdpConfig.getCustomTags()));

            body.setProfileRequest(profileRequest);

            //查询人的标签
            OnlineUserProfileRespWithPrivacy custResponse = api.getUserProfileWithPrivacy(body);
            log.info("cdp 返回人标签信息：{}", JSON.toJSONString(custResponse.getTags()));
            //解析人的标签信息
            tagsAndPropertiesMap = custResponse.getTags().entrySet().stream()
                    .collect(Collectors.toMap(
                            entry -> entry.getKey(),
                            entry -> entry.getValue().replaceAll("\"", "").replaceAll("\\[|\\]","")
                    ));
            Object temp = new ObjectMapper().readerFor(clazz).readValue(JSON.toJSONString(tagsAndPropertiesMap));
            BeanUtils.copyProperties(temp, cdpLeadsTagsProd, getNullPropertyNames(temp));

            String[] ownerVinArgs = new String[]{};
            if(StringUtils.isNotBlank(cdpLeadsTagsProd.getCurrentOwnerVin())){
                ownerVinArgs = cdpLeadsTagsProd.getCurrentOwnerVin().split(",");
            }
            int ownerVinArgsLen = ownerVinArgs.length;
            List<String> loyaltyList = new ArrayList<>();
            if(ownerVinArgs.length > 0){
                for (int i = 0; i < ownerVinArgsLen; i++) {
                    log.info("查询 忠诚度");
                    //忠诚度用现任车主vin转，是否购买金融方案用购车人vin转
                    profileRequest = new OnlineUserProfileRequest();
                    profileRequest.setIdType("vin");
                    profileRequest.setId(ownerVinArgs[i]);
                    profileRequest.setTags(cdpConfig.parsePropertiesOrTags(cdpConfig.getLoyalty()));

                    body.setProfileRequest(profileRequest);

                    //查询车 的忠诚度标签
                    OnlineUserProfileRespWithPrivacy carResponse = api.getUserProfileWithPrivacy(body);
                    log.info("cdp 返回 忠诚度标签信息：{}", JSON.toJSONString(carResponse.getTags()));
                    //解析车的标签信息
                    String tag = MapUtils.getString(carResponse.getTags(), cdpConfig.getLoyalty(), "").replaceAll("\"", "");
                    loyaltyList.add(tag);
                }
            }

            log.info("忠诚度标签信息：{}",  JSON.toJSONString(loyaltyList));
            if(loyaltyList.contains("忠诚")){
                cdpLeadsTagsProd.setLoyalty("忠诚（近1年至少1次记录）");
            } if(loyaltyList.contains("新")){
                cdpLeadsTagsProd.setLoyalty("新车（6个月内）");
            } else if(loyaltyList.contains("流失")){
                cdpLeadsTagsProd.setLoyalty("流失（其他）");
            }

            String[] buyerVinArgs = new String[]{};
            if(StringUtils.isNotBlank(cdpLeadsTagsProd.getCarBuyerVin())){
                buyerVinArgs = cdpLeadsTagsProd.getCarBuyerVin().split(",");
            }
            int buyerVinArgsLen = buyerVinArgs.length;
            if(buyerVinArgs.length > 0){
                for (int i = 0; i < buyerVinArgsLen; i++) {
                    log.info("查询 是否购买金融方案");
                    profileRequest = new OnlineUserProfileRequest();
                    profileRequest.setIdType("vin");
                    profileRequest.setId(buyerVinArgs[i]);
                    profileRequest.setTags(cdpConfig.parsePropertiesOrTags(cdpConfig.getIsBuyFinancial()));

                    body.setProfileRequest(profileRequest);

                    //查询车 的属性和标签
                    OnlineUserProfileRespWithPrivacy carResponse = api.getUserProfileWithPrivacy(body);
                    log.info("cdp 返回 是否购买金融方案标签信息：{}", JSON.toJSONString(carResponse.getTags()));
                    //解析车的标签信息
                    String tag = MapUtils.getString(carResponse.getTags(), cdpConfig.getIsBuyFinancial(), "").replaceAll("\"", "");
                    if("是".equals(tag)){
                        cdpLeadsTagsProd.setIsBuyFinancial("是");
                        break;
                    }
                }
            }

            log.info("设置标签缓存，{}", phone);
            RedisOps.set(cdpTagsKey, cdpLeadsTagsProd, 24, TimeUnit.HOURS);

        } catch (ApiException e) {
            log.error(e.getMessage());
            log.error("获取标签失败");
        } catch (JsonProcessingException e) {
            log.error(e.getMessage());
            log.error("解析标签失败");
        }
        log.info("标签：{}",  JSON.toJSONString(cdpLeadsTagsProd));
        return cdpLeadsTagsProd;
    }

    //获取到对象中值为null的属性名称
    public static String[] getNullPropertyNames (Object source) {
        final BeanWrapper src = new BeanWrapperImpl(source);
        java.beans.PropertyDescriptor[] pds = src.getPropertyDescriptors();

        Set<String> emptyNames = new HashSet<String>();
        for(java.beans.PropertyDescriptor pd : pds) {
            Object srcValue = src.getPropertyValue(pd.getName());
            if (srcValue == null) {
                emptyNames.add(pd.getName());
            }
        }
        String[] result = new String[emptyNames.size()];
        return emptyNames.toArray(result);
    }
}
