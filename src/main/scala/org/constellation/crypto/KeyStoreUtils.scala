package org.constellation.crypto

import java.io.{File, FileInputStream, FileOutputStream}
import java.security.cert.Certificate
import java.security.{KeyPair, KeyStore, PrivateKey}

import cats.effect._
import cats.implicits._
import org.constellation.crypto.cert.{DistinguishedName, SelfSignedCertificate}

object KeyStoreUtils {
  // TODO: kpudlik: Consider using platform-agnostic PKCS12 because JKS works only with Java. Or maybe make configurable.
  val storeType = "JKS"

  private def reader[F[_]: Sync](keyStorePath: String): Resource[F, FileInputStream] =
    Resource.fromAutoCloseable(Sync[F].delay {
      new FileInputStream(keyStorePath)
    })

  private def writer[F[_]: Sync](keyStorePath: String): Resource[F, FileOutputStream] =
    Resource.fromAutoCloseable(Sync[F].delay {
      val file = new File(keyStorePath)
      // TODO: Check if file exists
      new FileOutputStream(file)
    })

  private def generateKeyWithCertificate[F[_]: Sync]: F[(PrivateKey, Array[Certificate])] =
    Sync[F].delay {
      val keyPair = KeyUtils.makeKeyPair()
      // TODO: Maybe move to config
      val dn = DistinguishedName(
        commonName = "constellationnetwork.io",
        organization = "Constellation Labs"
      )

      val validity = 365 * 1000 // // 1000 years of validity should be enough I guess

      val certificate = SelfSignedCertificate.generate(dn.toString, keyPair, validity, KeyUtils.DefaultSignFunc)

      (keyPair.getPrivate, Array(certificate))
    }

  private def unlockKeyStore[F[_]: Sync](
    password: Array[Char]
  )(stream: FileInputStream): F[KeyStore] = Sync[F].delay {
    val keyStore = KeyStore.getInstance(storeType)
    keyStore.load(stream, password)
    keyStore
  }

  private def createEmptyKeyStore[F[_]: Sync](password: Array[Char]): F[KeyStore] = Sync[F].delay {
    val keyStore = KeyStore.getInstance(storeType)
    keyStore.load(null, password)
    keyStore
  }

  private def unlockKeyPair[F[_]: Sync](alias: String, keyPassword: Array[Char])(keyStore: KeyStore): F[KeyPair] =
    Sync[F].delay {
      val privateKey = keyStore.getKey(alias, keyPassword).asInstanceOf[PrivateKey]
      val publicKey = keyStore.getCertificate(alias).getPublicKey
      new KeyPair(publicKey, privateKey)
    }

  def createKeyStorageWithKeyPair[F[_]: Sync](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): F[KeyStore] =
    writer(path)
      .use(
        stream =>
          createEmptyKeyStore(storePassword)
            .flatMap(
              keyStore =>
                generateKeyWithCertificate
                  .map({
                    case (privateKey, chain) =>
                      keyStore.setKeyEntry(alias, privateKey, keyPassword, chain)
                      keyStore.store(stream, storePassword)
                      keyStore
                  })
          )
      )

  def getKeyPairFromKeyStore[F[_]: Sync](
    path: String,
    alias: String,
    storePassword: Array[Char],
    keyPassword: Array[Char]
  ): F[KeyPair] =
    reader(path)
      .evalMap(unlockKeyStore[F](storePassword))
      .evalMap(unlockKeyPair[F](alias, keyPassword))
      .use(_.pure[F])
}
