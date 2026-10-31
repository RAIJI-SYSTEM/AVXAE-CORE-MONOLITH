package com.raiji.users.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;

@Entity
@Table(name = "user_business_profiles")
public class UserBusinessProfile {

    @Id
    @Column(name = "sub", nullable = false, length = 64)
    private String sub;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    @Column(name = "update_at", nullable = false)
    private OffsetDateTime updateAt;

    public UserBusinessProfile(){

    }
    public UserBusinessProfile(String sub, String phone, String photoUrl){
        this.sub = sub;
        this.phone = phone;
        this.photoUrl = photoUrl;
        this.updateAt = OffsetDateTime.now();
    }

    public String getSub(){
        return sub;
    }
    public void setSub(String sub){
        this.sub = sub;
    }

    public String getPhone(){
        return phone;
    }
    public String getPhotoUrl(){
        return photoUrl;
    }
    public void setPhotoUrl(String photoUrl){
        this.photoUrl = photoUrl;
    }
    public void setUpdateAt(OffsetDateTime updateAt){
        this.updateAt = updateAt;
    }
    public void setPhone(String phone) {
        this.phone = phone;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updateAt = updatedAt;
    }
}
