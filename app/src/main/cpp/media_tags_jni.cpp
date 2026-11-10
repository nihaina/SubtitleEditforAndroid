#include <jni.h>
#include <android/log.h>
#include <unistd.h>

#include <limits>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>

#include "fileref.h"
#include "tag.h"
#include "tagunion.h"
#include "tfilestream.h"
#include "tpropertymap.h"
#include "tvariant.h"
#include "mpeg/mpegfile.h"
#include "mpeg/id3v2/id3v2tag.h"
#include "mpeg/id3v2/id3v2frame.h"
#include "mpeg/id3v2/frames/commentsframe.h"
#include "mpeg/id3v2/frames/podcastframe.h"
#include "mpeg/id3v2/frames/textidentificationframe.h"
#include "mpeg/id3v2/frames/uniquefileidentifierframe.h"
#include "mpeg/id3v2/frames/urllinkframe.h"
#include "mpeg/id3v2/frames/unsynchronizedlyricsframe.h"
#include "ape/apefile.h"
#include "ape/apetag.h"
#include "asf/asftag.h"
#include "flac/flacfile.h"
#include "mpc/mpcfile.h"
#include "mp4/mp4tag.h"
#include "ogg/xiphcomment.h"
#include "riff/wav/wavfile.h"
#include "riff/wav/infotag.h"
#include "dsdiff/dsdifffile.h"
#include "trueaudio/trueaudiofile.h"
#include "wavpack/wavpackfile.h"

namespace {
using TagLib::PropertyMap;
using TagLib::String;
using TagLib::StringList;
using Pictures = TagLib::List<TagLib::VariantMap>;

void logFailure(const char *operation, const char *message) {
    __android_log_print(ANDROID_LOG_ERROR, "MediaTagNative", "%s: %s", operation, message);
}

// FileStream takes ownership of the duplicate, never of the caller's descriptor.
std::unique_ptr<TagLib::FileStream> openStream(int borrowedFd, bool readOnly) {
    const int ownedFd = dup(borrowedFd);
    if (ownedFd < 0) throw std::runtime_error("Cannot duplicate media descriptor");
    std::unique_ptr<TagLib::FileStream> stream;
    try {
        stream = std::make_unique<TagLib::FileStream>(ownedFd, readOnly);
    } catch (...) {
        close(ownedFd);
        throw;
    }
    if (!stream->isOpen()) {
        close(ownedFd);
        throw std::runtime_error("Cannot open media descriptor");
    }
    stream->seek(0);
    return stream;
}

String fromJava(JNIEnv *env, jstring value) {
    if (!value) throw std::invalid_argument("Null tag string");
    const jsize length = env->GetStringLength(value);
    const jchar *characters = env->GetStringChars(value, nullptr);
    if (!characters) throw std::runtime_error("Cannot read tag string");
    std::wstring text;
    try {
        text.reserve(length);
        for (jsize index = 0; index < length; ++index) text.push_back(characters[index]);
    } catch (...) {
        env->ReleaseStringChars(value, characters);
        throw;
    }
    env->ReleaseStringChars(value, characters);
    // TagLib's wstring stores UTF-16 code units even when wchar_t is 32 bits.
    return String(text);
}

jstring toJava(JNIEnv *env, const String &value) {
    const std::wstring text = value.toWString();
    if (text.size() > static_cast<size_t>(std::numeric_limits<jsize>::max()))
        throw std::length_error("Tag string is too long");
    std::vector<jchar> characters(text.begin(), text.end());
    static constexpr jchar empty = 0;
    return env->NewString(characters.empty() ? &empty : characters.data(),
                          static_cast<jsize>(characters.size()));
}

// TagUnion normally returns only the first nonempty tag. A WAV containing just
// ID3 lyrics must still expose its title/artist/album from the RIFF INFO block.
void mergeAbsent(PropertyMap &target, const PropertyMap &source) {
    for (const auto &[key, values] : source) {
        if (!target.contains(key)) target.insert(key, values);
    }
}

PropertyMap propertiesFor(TagLib::File *file) {
    PropertyMap properties = file->properties();
    if (auto tags = dynamic_cast<TagLib::TagUnion *>(file->tag())) {
        for (int index = 0; index < 3; ++index) {
            if (auto tag = tags->tag(index)) mergeAbsent(properties, tag->properties());
        }
    }
    return properties;
}

void applyEdits(PropertyMap &properties, const PropertyMap &edits) {
    for (const auto &[key, values] : edits) {
        if (values.isEmpty()) properties.erase(key);
        else properties.replace(key, values);
    }
}

PropertyMap editableFrameProperties(TagLib::ID3v2::Frame *frame) {
    // Frame's base method is protected, while the editable frame classes expose
    // their format-independent properties publicly. Unknown/binary/chapter
    // frames have no editable fields and stay detached throughout the update.
    using namespace TagLib::ID3v2;
    if (auto text = dynamic_cast<TextIdentificationFrame *>(frame)) return text->asProperties();
    if (auto comment = dynamic_cast<CommentsFrame *>(frame)) return comment->asProperties();
    if (auto lyrics = dynamic_cast<UnsynchronizedLyricsFrame *>(frame)) return lyrics->asProperties();
    if (auto url = dynamic_cast<UrlLinkFrame *>(frame)) return url->asProperties();
    if (auto identifier = dynamic_cast<UniqueFileIdentifierFrame *>(frame)) return identifier->asProperties();
    if (auto podcast = dynamic_cast<PodcastFrame *>(frame)) return podcast->asProperties();
    return PropertyMap();
}

// Protect untouched ID3 frames from PropertyMap's normalization. In particular,
// duplicate USLT frames may share a description but have different languages;
// converting their merged property back to frames would lose that distinction.
PropertyMap updateId3(TagLib::ID3v2::Tag *tag, const PropertyMap &edits) {
    struct PreservedFrames {
        TagLib::ID3v2::Tag *tag;
        std::vector<TagLib::ID3v2::Frame *> frames;
        ~PreservedFrames() {
            for (auto frame : frames) tag->addFrame(frame);
        }
    } preserved{tag, {}};
    // TagLib's FrameList owns its pointers. Copying it shares an owning list
    // which may delete frames after removeFrame detaches the tag's list.
    const auto &originalFrames = tag->frameList();
    const std::vector<TagLib::ID3v2::Frame *> frames(originalFrames.begin(), originalFrames.end());
    preserved.frames.reserve(frames.size());
    for (auto frame : frames) {
        bool affected = false;
        for (const auto &[key, values] : editableFrameProperties(frame)) {
            if (edits.contains(key)) { affected = true; break; }
        }
        if (!affected) {
            preserved.frames.push_back(frame);
            tag->removeFrame(frame, false);
        }
    }
    PropertyMap properties = tag->properties();
    applyEdits(properties, edits);
    return tag->setProperties(properties);
}

bool containsEditedKey(const PropertyMap &properties, const PropertyMap &edits) {
    for (const auto &[key, values] : properties) {
        if (edits.contains(key)) return true;
    }
    return false;
}

PropertyMap updateAsf(TagLib::ASF::Tag *tag, const PropertyMap &edits) {
    struct PreservedAttributes {
        TagLib::ASF::Tag *tag;
        TagLib::ASF::AttributeListMap attributes;
        ~PreservedAttributes() {
            for (const auto &[name, values] : attributes) tag->setAttribute(name, values);
        }
    } preserved{tag, {}};
    const auto original = tag->attributeListMap();
    for (const auto &[name, values] : original) {
        TagLib::ASF::Tag probe;
        probe.setAttribute(name, values);
        if (!containsEditedKey(probe.properties(), edits)) preserved.attributes.insert(name, values);
        tag->removeItem(name);
    }
    // Only the four ASF content-description strings remain. Native attributes
    // outside the edit are restored with their type, language and stream intact.
    PropertyMap properties = tag->properties();
    applyEdits(properties, edits);
    return tag->setProperties(properties);
}

PropertyMap updateMp4(TagLib::MP4::Tag *tag, const PropertyMap &edits) {
    struct PreservedItems {
        TagLib::MP4::Tag *tag;
        TagLib::MP4::ItemMap items;
        ~PreservedItems() {
            for (const auto &[name, item] : items) tag->setItem(name, item);
        }
    } preserved{tag, {}};
    const auto original = tag->itemMap();
    for (const auto &[name, item] : original) {
        TagLib::MP4::Tag probe;
        probe.setItem(name, item);
        if (!containsEditedKey(probe.properties(), edits)) preserved.items.insert(name, item);
        tag->removeItem(name);
    }
    // Recreate edited fields only. Binary freeforms and other unedited atoms
    // must never pass through PropertyMap's string-only representation.
    PropertyMap properties;
    applyEdits(properties, edits);
    return tag->setProperties(properties);
}

PropertyMap updateTag(TagLib::Tag *tag, const PropertyMap &edits) {
    if (auto id3 = dynamic_cast<TagLib::ID3v2::Tag *>(tag)) return updateId3(id3, edits);
    if (auto asf = dynamic_cast<TagLib::ASF::Tag *>(tag)) return updateAsf(asf, edits);
    if (auto mp4 = dynamic_cast<TagLib::MP4::Tag *>(tag)) return updateMp4(mp4, edits);
    PropertyMap properties = tag->properties();
    applyEdits(properties, edits);
    return tag->setProperties(properties);
}

// Create the container's normal writable tag before editing a file with only a
// legacy tag. Secondary tags are updated too, so old values cannot win in other
// players that prefer a different tag family.
TagLib::Tag *primaryTag(TagLib::File *file) {
    if (auto mpeg = dynamic_cast<TagLib::MPEG::File *>(file)) return mpeg->ID3v2Tag(true);
    if (auto wav = dynamic_cast<TagLib::RIFF::WAV::File *>(file)) return wav->ID3v2Tag();
    if (auto flac = dynamic_cast<TagLib::FLAC::File *>(file)) return flac->xiphComment(true);
    if (auto ape = dynamic_cast<TagLib::APE::File *>(file)) return ape->APETag(true);
    if (auto mpc = dynamic_cast<TagLib::MPC::File *>(file)) return mpc->APETag(true);
    if (auto wavpack = dynamic_cast<TagLib::WavPack::File *>(file)) return wavpack->APETag(true);
    if (auto tta = dynamic_cast<TagLib::TrueAudio::File *>(file)) return tta->ID3v2Tag(true);
    if (auto dsdiff = dynamic_cast<TagLib::DSDIFF::File *>(file)) return dsdiff->ID3v2Tag(true);
    return file->tag();
}

bool updateProperties(TagLib::File *file, const PropertyMap &edits) {
    if (edits.isEmpty()) return true;
    const PropertyMap original = propertiesFor(file);
    TagLib::Tag *primary = primaryTag(file);
    if (!primary) return false;
    PropertyMap primaryEdits(edits);
    const PropertyMap primaryOriginal = primary->properties();
    for (const auto &[key, values] : original) {
        if (!primaryOriginal.contains(key) && !primaryEdits.contains(key))
            primaryEdits.insert(key, values);
    }
    const PropertyMap ignored = updateTag(primary, primaryEdits);
    for (const auto &[key, values] : edits) {
        if (!values.isEmpty() && ignored.contains(key)) return false;
    }
    if (auto tags = dynamic_cast<TagLib::TagUnion *>(file->tag())) {
        for (int index = 0; index < 3; ++index) {
            if (auto tag = tags->tag(index); tag && tag != primary) updateTag(tag, edits);
        }
    }
    return true;
}

bool tagSupportsPictures(TagLib::Tag *tag) {
    return dynamic_cast<TagLib::ID3v2::Tag *>(tag) ||
           dynamic_cast<TagLib::Ogg::XiphComment *>(tag) ||
           dynamic_cast<TagLib::APE::Tag *>(tag) ||
           dynamic_cast<TagLib::ASF::Tag *>(tag) ||
           dynamic_cast<TagLib::MP4::Tag *>(tag);
}

Pictures editedPictures(const Pictures &original, int mode, const TagLib::VariantMap &front) {
    Pictures pictures;
    if (mode == 2) {
        for (const auto &picture : original) {
            const String type = picture.value("pictureType").toString();
            // MP4 has no picture type; its artwork entries are treated as covers.
            if (!type.isEmpty() && type != "Front Cover") pictures.append(picture);
        }
        pictures.append(front);
    }
    return pictures;
}

bool updatePictures(TagLib::File *file, int mode, const TagLib::VariantMap &front) {
    if (mode == 0) return true;
    TagLib::Tag *primary = primaryTag(file);
    if (!primary || !tagSupportsPictures(primary)) return false;
    if (dynamic_cast<TagLib::FLAC::File *>(file)) {
        // Native FLAC picture blocks live outside the Vorbis comment tag.
        if (!file->setComplexProperties("PICTURE", editedPictures(file->complexProperties("PICTURE"), mode, front)))
            return false;
        // Also remove/replace legacy tag pictures when present.
        if (auto tags = dynamic_cast<TagLib::TagUnion *>(file->tag())) {
            for (int index = 0; index < 3; ++index) {
                if (auto tag = tags->tag(index); tag && !tag->complexProperties("PICTURE").isEmpty())
                    tag->setComplexProperties("PICTURE", editedPictures(tag->complexProperties("PICTURE"), mode, front));
            }
        }
        return true;
    }
    if (!primary->setComplexProperties("PICTURE", editedPictures(primary->complexProperties("PICTURE"), mode, front)))
        return false;
    if (auto tags = dynamic_cast<TagLib::TagUnion *>(file->tag())) {
        for (int index = 0; index < 3; ++index) {
            if (auto tag = tags->tag(index); tag && tag != primary && !tag->complexProperties("PICTURE").isEmpty())
                tag->setComplexProperties("PICTURE", editedPictures(tag->complexProperties("PICTURE"), mode, front));
        }
    }
    return true;
}

PropertyMap javaEdits(JNIEnv *env, jobjectArray keys, jobjectArray values) {
    if (!keys || !values || env->GetArrayLength(keys) != env->GetArrayLength(values))
        throw std::invalid_argument("Tag edit arrays do not match");
    PropertyMap edits;
    for (jsize index = 0; index < env->GetArrayLength(keys); ++index) {
        auto keyObject = static_cast<jstring>(env->GetObjectArrayElement(keys, index));
        auto valueObject = static_cast<jstring>(env->GetObjectArrayElement(values, index));
        String key = fromJava(env, keyObject).upper();
        String value = fromJava(env, valueObject);
        env->DeleteLocalRef(keyObject);
        env->DeleteLocalRef(valueObject);
        if (key.isEmpty() || !edits.replace(key, value.isEmpty() ? StringList() : StringList(value)))
            throw std::invalid_argument("Invalid tag property key");
    }
    return edits;
}

TagLib::VariantMap javaCover(JNIEnv *env, jbyteArray data, jstring mime) {
    constexpr jsize maxCoverBytes = 64 * 1024 * 1024;
    if (!data || env->GetArrayLength(data) < 1 || env->GetArrayLength(data) > maxCoverBytes)
        throw std::invalid_argument("Invalid cover image size");
    TagLib::ByteVector bytes(static_cast<unsigned int>(env->GetArrayLength(data)), 0);
    env->GetByteArrayRegion(data, 0, env->GetArrayLength(data), reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) throw std::runtime_error("Cannot read cover image");
    TagLib::VariantMap picture;
    picture.insert("data", bytes);
    picture.insert("mimeType", fromJava(env, mime));
    picture.insert("description", String());
    picture.insert("pictureType", String("Front Cover"));
    return picture;
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_subtitleedit_MediaTagNative_read(JNIEnv *env, jobject, jint fd) {
    try {
        auto stream = openStream(fd, true);
        TagLib::FileRef reference(stream.get(), false);
        if (reference.isNull()) return nullptr;
        const PropertyMap properties = propertiesFor(reference.file());
        size_t count = 0;
        for (const auto &[key, values] : properties) count += values.size() * 2;
        if (count > static_cast<size_t>(std::numeric_limits<jsize>::max())) return nullptr;
        jclass stringClass = env->FindClass("java/lang/String");
        if (!stringClass) return nullptr;
        auto result = env->NewObjectArray(static_cast<jsize>(count), stringClass, nullptr);
        env->DeleteLocalRef(stringClass);
        if (!result) return nullptr;
        jsize index = 0;
        for (const auto &[key, values] : properties) {
            for (const auto &value : values) {
                jstring keyObject = toJava(env, key.upper());
                jstring valueObject = toJava(env, value);
                if (env->ExceptionCheck()) return nullptr;
                env->SetObjectArrayElement(result, index++, keyObject);
                env->SetObjectArrayElement(result, index++, valueObject);
                env->DeleteLocalRef(keyObject);
                env->DeleteLocalRef(valueObject);
                if (env->ExceptionCheck()) return nullptr;
            }
        }
        return result;
    } catch (const std::exception &error) { logFailure("read", error.what()); }
      catch (...) { logFailure("read", "Unknown native error"); }
    return nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_subtitleedit_MediaTagNative_pictures(JNIEnv *env, jobject, jint fd) {
    try {
        auto stream = openStream(fd, true);
        TagLib::FileRef reference(stream.get(), false);
        const Pictures pictures = reference.isNull() ? Pictures() : reference.complexProperties("PICTURE");
        jclass pictureClass = env->FindClass("com/subtitleedit/MediaTagPicture");
        if (!pictureClass) return nullptr;
        jmethodID constructor = env->GetMethodID(pictureClass, "<init>",
            "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
        if (!constructor) return nullptr;
        auto result = env->NewObjectArray(static_cast<jsize>(pictures.size()), pictureClass, nullptr);
        if (!result) return nullptr;
        jsize index = 0;
        for (const auto &picture : pictures) {
            const TagLib::ByteVector bytes = picture.value("data").toByteVector();
            if (bytes.size() > static_cast<unsigned int>(std::numeric_limits<jsize>::max())) return nullptr;
            jbyteArray data = env->NewByteArray(static_cast<jsize>(bytes.size()));
            if (!data) return nullptr;
            env->SetByteArrayRegion(data, 0, static_cast<jsize>(bytes.size()), reinterpret_cast<const jbyte *>(bytes.data()));
            jstring mime = toJava(env, picture.value("mimeType").toString());
            jstring description = toJava(env, picture.value("description").toString());
            jstring type = toJava(env, picture.value("pictureType").toString());
            if (env->ExceptionCheck()) return nullptr;
            jobject item = env->NewObject(pictureClass, constructor, data, mime, description, type);
            if (!item) return nullptr;
            env->SetObjectArrayElement(result, index++, item);
            env->DeleteLocalRef(data);
            env->DeleteLocalRef(mime);
            env->DeleteLocalRef(description);
            env->DeleteLocalRef(type);
            env->DeleteLocalRef(item);
            if (env->ExceptionCheck()) return nullptr;
        }
        env->DeleteLocalRef(pictureClass);
        return result;
    } catch (const std::exception &error) { logFailure("pictures", error.what()); }
      catch (...) { logFailure("pictures", "Unknown native error"); }
    return nullptr;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_subtitleedit_MediaTagNative_supportsCover(JNIEnv *, jobject, jint fd) {
    try {
        auto stream = openStream(fd, true);
        TagLib::FileRef reference(stream.get(), false);
        return !reference.isNull() && tagSupportsPictures(primaryTag(reference.file()));
    } catch (const std::exception &error) { logFailure("supportsCover", error.what()); }
      catch (...) { logFailure("supportsCover", "Unknown native error"); }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_subtitleedit_MediaTagNative_write(JNIEnv *env, jobject, jint fd,
    jobjectArray keys, jobjectArray values, jint coverMode, jbyteArray coverData, jstring coverMime) {
    try {
        if (coverMode < 0 || coverMode > 2) return JNI_FALSE;
        const PropertyMap edits = javaEdits(env, keys, values);
        const TagLib::VariantMap front = coverMode == 2 ? javaCover(env, coverData, coverMime) : TagLib::VariantMap();
        {
            auto stream = openStream(fd, false);
            if (stream->readOnly()) return JNI_FALSE;
            TagLib::FileRef reference(stream.get(), false);
            if (reference.isNull() || !updateProperties(reference.file(), edits) ||
                !updatePictures(reference.file(), coverMode, front) || !reference.save()) return JNI_FALSE;
        } // fclose flushes TagLib's buffered writes before Kotlin performs readback.
        return fsync(fd) == 0 ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception &error) { logFailure("write", error.what()); }
      catch (...) { logFailure("write", "Unknown native error"); }
    return JNI_FALSE;
}
