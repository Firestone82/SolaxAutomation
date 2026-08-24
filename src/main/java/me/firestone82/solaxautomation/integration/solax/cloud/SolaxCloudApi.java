package me.firestone82.solaxautomation.integration.solax.cloud;

import me.firestone82.solaxautomation.integration.solax.cloud.model.*;
import me.firestone82.solaxautomation.integration.solax.cloud.model.request.*;
import retrofit2.Call;
import retrofit2.http.*;

import java.util.List;
import java.util.Map;

/**
 * Retrofit binding of the SolaX Cloud OpenAPI endpoints this application uses.
 * <p>
 * Every call except {@link #obtainToken} needs the {@code Authorization: bearer <token>}
 * header, which {@link SolaxCloudTokenProvider} adds through an OkHttp interceptor.
 *
 * @see <a href="https://developer.solaxcloud.com/doc">SolaX developer portal</a>
 */
public interface SolaxCloudApi {

    // ------------------------------------------------------------------ auth

    @FormUrlEncoded
    @POST("openapi/auth/oauth/token")
    Call<CloudResponse<TokenResult>> obtainToken(
            @Field("client_id") String clientId,
            @Field("client_secret") String clientSecret,
            @Field("grant_type") String grantType
    );

    // ------------------------------------------------------------------ monitoring

    @GET("openapi/v2/plant/realtime_data")
    Call<CloudResponse<PlantRealtimeData>> getPlantRealtimeData(
            @Query("plantId") String plantId,
            @Query("businessType") int businessType
    );

    @GET("openapi/v2/device/realtime_data")
    Call<CloudResponse<List<InverterRealtimeData>>> getInverterRealtimeData(
            @Query("snList") String snList,
            @Query("deviceType") int deviceType,
            @Query("businessType") int businessType
    );

    @GET("openapi/v2/device/realtime_data")
    Call<CloudResponse<List<BatteryRealtimeData>>> getBatteryRealtimeData(
            @Query("snList") String snList,
            @Query("deviceType") int deviceType,
            @Query("requestSnType") Integer requestSnType,
            @Query("businessType") int businessType
    );

    // ------------------------------------------------------------------ work mode

    @POST("openapi/v2/device/inverter_work_mode/batch_set_spontaneity_self_use")
    Call<CloudResponse<Map<String, CommandResult>>> setSelfUseMode(@Body WorkModeRequest request);

    @POST("openapi/v2/device/inverter_work_mode/batch_set_on_grid_first")
    Call<CloudResponse<Map<String, CommandResult>>> setFeedInPriorityMode(@Body WorkModeRequest request);

    @POST("openapi/v2/device/inverter_work_mode/batch_set_peace_mode")
    Call<CloudResponse<Map<String, CommandResult>>> setBackUpMode(@Body WorkModeRequest request);

    @POST("openapi/v2/device/inverter_work_mode/batch_set_manual_mode")
    Call<CloudResponse<Map<String, CommandResult>>> setManualMode(@Body ManualModeRequest request);

    // ------------------------------------------------------------------ remote control

    @POST("openapi/v2/device/inverter_vpp_mode/push_power/positive_or_negative_mode")
    Call<CloudResponse<Map<String, CommandResult>>> pushPower(@Body PushPowerRequest request);

    @POST("openapi/v2/device/inverter_vpp_mode/soc_target_control_mode")
    Call<CloudResponse<Map<String, CommandResult>>> socTarget(@Body SocTargetRequest request);

    @POST("openapi/v2/device/inverter_vpp_mode/exit_vpp_mode")
    Call<CloudResponse<Map<String, CommandResult>>> exitRemoteControl(@Body ExitRemoteControlRequest request);

    // ------------------------------------------------------------------ export limit

    @POST("openapi/v2/device/device_control/strategy/set_export_control")
    Call<CloudResponse<Map<String, CommandResult>>> setExportControl(@Body ExportControlRequest request);
}
