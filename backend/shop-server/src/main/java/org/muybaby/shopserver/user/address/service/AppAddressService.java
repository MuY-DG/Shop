package org.muybaby.shopserver.user.address.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import org.muybaby.shopserver.common.error.BusinessException;
import org.muybaby.shopserver.common.error.ErrorCode;
import org.muybaby.shopserver.user.address.dto.AddressResponse;
import org.muybaby.shopserver.user.address.dto.AddressUpsertRequest;
import org.muybaby.shopserver.user.address.entity.UserAddress;
import org.muybaby.shopserver.user.address.mapper.UserAddressMapper;
import org.muybaby.shopserver.user.entity.AppUser;
import org.muybaby.shopserver.user.mapper.AppUserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class AppAddressService {

    private static final String ENABLED_STATUS = "ENABLED";

    private final AppUserMapper appUserMapper;
    private final UserAddressMapper userAddressMapper;

    public AppAddressService(
            AppUserMapper appUserMapper,
            UserAddressMapper userAddressMapper
    ) {
        this.appUserMapper = appUserMapper;
        this.userAddressMapper = userAddressMapper;
    }

    @Transactional(readOnly = true)
    public List<AddressResponse> list(long userId) {
        return userAddressMapper.selectByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public AddressResponse get(long userId, long addressId) {
        return toResponse(requireOwned(userId, addressId));
    }

    @Transactional
    public AddressResponse create(long userId, AddressUpsertRequest request) {
        List<UserAddress> lockedAddresses = lockAddressBook(userId);
        boolean makeDefault = lockedAddresses.isEmpty() || request.isDefault();
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        if (makeDefault && !lockedAddresses.isEmpty()) {
            userAddressMapper.clearDefaults(userId, now);
        }
        UserAddress address = new UserAddress(
                IdWorker.getId(),
                userId,
                request.receiverName(),
                request.receiverPhone(),
                request.province(),
                request.city(),
                request.district(),
                request.detailAddress(),
                request.locationName(),
                request.doorplate(),
                makeDefault,
                now,
                now
        );
        requireOneRow(userAddressMapper.insert(address));
        return toResponse(address);
    }

    @Transactional
    public AddressResponse update(long userId, long addressId, AddressUpsertRequest request) {
        List<UserAddress> lockedAddresses = lockAddressBook(userId);
        UserAddress existing = findLockedOwned(lockedAddresses, addressId);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        boolean makeDefault = request.isDefault() || Boolean.TRUE.equals(existing.isDefault());
        if (request.isDefault()) {
            userAddressMapper.clearDefaults(userId, now);
        }
        UserAddress updated = new UserAddress(
                existing.id(),
                existing.userId(),
                request.receiverName(),
                request.receiverPhone(),
                request.province(),
                request.city(),
                request.district(),
                request.detailAddress(),
                request.locationName(),
                request.doorplate(),
                makeDefault,
                existing.createdAt(),
                now
        );
        requireOneRow(userAddressMapper.updateById(updated));
        return toResponse(updated);
    }

    @Transactional
    public void delete(long userId, long addressId) {
        List<UserAddress> lockedAddresses = lockAddressBook(userId);
        UserAddress existing = findLockedOwned(lockedAddresses, addressId);
        int deleted = userAddressMapper.deleteOwned(userId, addressId);
        requireOneRow(deleted);
        if (Boolean.TRUE.equals(existing.isDefault())) {
            lockedAddresses.stream()
                    .filter(address -> !address.id().equals(addressId))
                    .findFirst()
                    .ifPresent(address -> setDefaultFlag(address, true, LocalDateTime.now(java.time.ZoneOffset.UTC)));
        }
    }

    @Transactional
    public AddressResponse setDefault(long userId, long addressId) {
        List<UserAddress> lockedAddresses = lockAddressBook(userId);
        UserAddress existing = findLockedOwned(lockedAddresses, addressId);
        LocalDateTime now = LocalDateTime.now(java.time.ZoneOffset.UTC);
        userAddressMapper.clearDefaults(userId, now);
        UserAddress updated = setDefaultFlag(existing, true, now);
        return toResponse(updated);
    }

    @Transactional
    public OwnedAddress requireOwnedForUpdate(long userId, long addressId) {
        List<UserAddress> lockedAddresses = lockAddressBook(userId);
        UserAddress address = findLockedOwned(lockedAddresses, addressId);
        return new OwnedAddress(
                address.id(),
                address.userId(),
                address.receiverName(),
                address.receiverPhone(),
                formattedAddress(address)
        );
    }

    private List<UserAddress> lockAddressBook(long userId) {
        AppUser user = appUserMapper.selectByIdForUpdate(userId);
        if (user == null || !ENABLED_STATUS.equals(user.status())) {
            throw new BusinessException(ErrorCode.AUTHENTICATION_REQUIRED);
        }
        return userAddressMapper.selectByUserIdForUpdate(userId);
    }

    private UserAddress requireOwned(long userId, long addressId) {
        UserAddress address = userAddressMapper.selectOwned(userId, addressId);
        if (address == null) {
            throw addressNotFound();
        }
        return address;
    }

    private UserAddress findLockedOwned(List<UserAddress> addresses, long addressId) {
        return addresses.stream()
                .filter(address -> address.id().equals(addressId))
                .findFirst()
                .orElseThrow(this::addressNotFound);
    }

    private UserAddress setDefaultFlag(UserAddress address, boolean isDefault, LocalDateTime updatedAt) {
        UserAddress updated = new UserAddress(
                address.id(),
                address.userId(),
                address.receiverName(),
                address.receiverPhone(),
                address.province(),
                address.city(),
                address.district(),
                address.detailAddress(),
                address.locationName(),
                address.doorplate(),
                isDefault,
                address.createdAt(),
                updatedAt
        );
        requireOneRow(userAddressMapper.updateById(updated));
        return updated;
    }

    private AddressResponse toResponse(UserAddress address) {
        return new AddressResponse(
                address.id(),
                address.receiverName(),
                address.receiverPhone(),
                address.province(),
                address.city(),
                address.district(),
                address.detailAddress(),
                address.locationName(),
                address.doorplate(),
                Boolean.TRUE.equals(address.isDefault()),
                formattedAddress(address),
                address.createdAt(),
                address.updatedAt()
        );
    }

    private String formattedAddress(UserAddress address) {
        String detail = address.detailAddress();
        if (!address.locationName().isBlank() && !detail.contains(address.locationName())) {
            detail = detail + " " + address.locationName();
        }
        if (!address.doorplate().isBlank() && !detail.endsWith(address.doorplate())) {
            detail = detail + " " + address.doorplate();
        }
        return address.province()
                + address.city()
                + address.district()
                + detail;
    }

    private BusinessException addressNotFound() {
        return new BusinessException(ErrorCode.VALIDATION_FAILED);
    }

    private void requireOneRow(int affectedRows) {
        if (affectedRows != 1) {
            throw addressNotFound();
        }
    }
}
